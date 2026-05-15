package com.firstember.fantasyfootball.sleeper;

import com.firstember.fantasyfootball.domain.Player;
import com.firstember.fantasyfootball.domain.PlayerStat;
import com.firstember.fantasyfootball.domain.Team;
import com.firstember.fantasyfootball.repo.PlayerRepository;
import com.firstember.fantasyfootball.repo.PlayerStatRepository;
import com.firstember.fantasyfootball.repo.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SleeperService {

    private static final Logger log = LoggerFactory.getLogger(SleeperService.class);

    private static final String BASE = "https://api.sleeper.app/v1";

    /** Only sync skill-position players. DST/LB/DB etc. are excluded. */
    private static final Set<String> KEEP_POSITIONS = Set.of("QB", "RB", "WR", "TE", "K");

    /** Regular season weeks — Sleeper uses 1-18 for the 18-game schedule. */
    private static final int REGULAR_SEASON_WEEKS = 18;

    /**
     * Sleeper uses different team abbreviations than our DB for a few teams.
     * Map Sleeper code → our DB code.
     */
    private static final Map<String, String> TEAM_CODE_FIX = Map.of(
            "LVR", "LV",
            "JAC", "JAX"
    );

    private final RestTemplate restTemplate;
    private final PlayerRepository playerRepository;
    private final PlayerStatRepository playerStatRepository;
    private final TeamRepository teamRepository;

    public SleeperService(RestTemplateBuilder builder,
                          PlayerRepository playerRepository,
                          PlayerStatRepository playerStatRepository,
                          TeamRepository teamRepository) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(90))
                .build();
        this.playerRepository    = playerRepository;
        this.playerStatRepository = playerStatRepository;
        this.teamRepository      = teamRepository;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Full sync for a given NFL season year (e.g. 2024).
     * Fetches all players + weekly stats from Sleeper, upserts into the DB,
     * and recalculates overall fantasy-point rankings.
     *
     * @return human-readable summary string
     */
    @Transactional(timeout = 600)
    public String syncSeason(int year) {
        log.info("=== Sleeper sync starting — season {} ===", year);

        // 1) Fetch master player registry from Sleeper
        Map<String, SleeperPlayerDTO> sleeperPlayers = fetchAllPlayers();
        log.info("Fetched {} total players from Sleeper", sleeperPlayers.size());

        // 2) Accumulate stats across all regular-season weeks
        Map<String, SleeperStatsDTO> seasonStats = accumulateSeasonStats(year);
        log.info("Accumulated stats for {} players over {} weeks", seasonStats.size(), REGULAR_SEASON_WEEKS);

        // 3) Build a team-code lookup map
        Map<String, Team> teamByCode = new HashMap<>();
        teamRepository.findAll().forEach(t -> teamByCode.put(t.getCode(), t));

        // 4) Upsert players + stats for anyone with PPR points this season
        int created = 0, updated = 0, skipped = 0;

        for (Map.Entry<String, SleeperStatsDTO> entry : seasonStats.entrySet()) {
            String sleeperPlayerId = entry.getKey();
            SleeperStatsDTO stats  = entry.getValue();

            if (!stats.hasStats()) { skipped++; continue; }

            SleeperPlayerDTO playerDTO = sleeperPlayers.get(sleeperPlayerId);
            if (playerDTO == null)                              { skipped++; continue; }

            String position = playerDTO.getPosition();
            if (position == null || !KEEP_POSITIONS.contains(position)) { skipped++; continue; }

            String fullName = playerDTO.getFullName();
            if (fullName == null || fullName.isBlank())         { skipped++; continue; }

            try {
                Player player = upsertPlayer(playerDTO, sleeperPlayerId, year, teamByCode);
                boolean wasNew = upsertStat(player, stats, year);
                if (wasNew) created++; else updated++;
            } catch (Exception e) {
                log.warn("Skipping player {} ({}): {}", fullName, sleeperPlayerId, e.getMessage());
                skipped++;
            }
        }

        log.info("Upsert done — created={}, updated={}, skipped={}", created, updated, skipped);

        // 5) Recalculate overall + positional ranks
        int ranked = assignRanks(year);
        log.info("Assigned ranks to {} players for season {}", ranked, year);

        return String.format(
                "Season %d sync complete — %d players created, %d updated, %d ranked.",
                year, created, updated, ranked);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** GET /players/nfl → Map<sleeper_player_id, SleeperPlayerDTO> */
    @SuppressWarnings("unchecked")
    private Map<String, SleeperPlayerDTO> fetchAllPlayers() {
        String url = BASE + "/players/nfl";
        Map<String, SleeperPlayerDTO> result =
                restTemplate.exchange(url, HttpMethod.GET, null,
                        new ParameterizedTypeReference<Map<String, SleeperPlayerDTO>>() {})
                        .getBody();
        return result != null ? result : Collections.emptyMap();
    }

    /**
     * Fetch weeks 1-{@value #REGULAR_SEASON_WEEKS} and accumulate stats per player.
     * GET /stats/nfl/regular/{year}/{week} → Map<player_id, Map<stat_key, value>>
     */
    private Map<String, SleeperStatsDTO> accumulateSeasonStats(int year) {
        Map<String, SleeperStatsDTO> totals = new HashMap<>();

        for (int week = 1; week <= REGULAR_SEASON_WEEKS; week++) {
            String url = BASE + "/stats/nfl/regular/" + year + "/" + week;
            try {
                Map<String, Map<String, Object>> weekData =
                        restTemplate.exchange(url, HttpMethod.GET, null,
                                new ParameterizedTypeReference<Map<String, Map<String, Object>>>() {})
                                .getBody();

                if (weekData == null) continue;

                weekData.forEach((playerId, statMap) ->
                        totals.computeIfAbsent(playerId, k -> new SleeperStatsDTO())
                              .addWeek(statMap));

                log.debug("Week {} — {} players with stats", week, weekData.size());
            } catch (Exception e) {
                log.warn("Failed to fetch week {} stats for {}: {}", week, year, e.getMessage());
            }
        }
        return totals;
    }

    /**
     * Find-or-create player, update all mutable fields, and save.
     * Lookup priority:
     *   1. externalId + season  (exact Sleeper-sourced match)
     *   2. fullName + position + season  (existing CSV-imported player without externalId)
     *   3. Create new record
     */
    private Player upsertPlayer(SleeperPlayerDTO dto, String extId, int year,
                                Map<String, Team> teamByCode) {

        // Normalize Sleeper team code to our DB code
        String rawTeam  = dto.getTeam();
        String teamCode = rawTeam != null ? TEAM_CODE_FIX.getOrDefault(rawTeam, rawTeam) : null;
        Team   team     = teamCode != null ? teamByCode.get(teamCode) : null;

        // Lookup existing record
        Optional<Player> found = playerRepository.findByExternalIdAndSeason(extId, year);
        if (found.isEmpty()) {
            found = playerRepository.findFirstByFullNameAndPositionAndSeason(
                    dto.getFullName(), dto.getPosition(), year);
        }

        Player player = found.orElse(new Player());
        player.setExternalId(extId);
        player.setFullName(dto.getFullName());
        player.setPosition(dto.getPosition());
        player.setTeam(team);
        player.setSeason(year);
        player.setAge(dto.getAge());
        player.setStatus(dto.getStatus());

        return playerRepository.save(player);
    }

    /**
     * Find-or-create PlayerStat for this player+season, populate all stat fields.
     * @return true if a new record was created, false if an existing one was updated
     */
    private boolean upsertStat(Player player, SleeperStatsDTO dto, int year) {
        Optional<PlayerStat> existing = playerStatRepository.findByPlayer_IdAndSeason(player.getId(), year);
        boolean isNew = existing.isEmpty();
        PlayerStat stat = existing.orElse(new PlayerStat());

        stat.setPlayer(player);
        stat.setSeason(year);
        stat.setTotalPoints(dto.getTotalPoints() > 0 ? roundTo2(dto.getTotalPoints()) : null);

        // Passing
        stat.setPassingYds(nullIfZero(dto.getPassingYds()));
        stat.setPassingTd(nullIfZero(dto.getPassingTd()));
        stat.setPassingInt(nullIfZero(dto.getPassingInt()));

        // Rushing
        stat.setRushingYds(nullIfZero(dto.getRushingYds()));
        stat.setRushingTd(nullIfZero(dto.getRushingTd()));

        // Receiving
        stat.setReceivingRec(nullIfZero(dto.getReceivingRec()));
        stat.setReceivingYds(nullIfZero(dto.getReceivingYds()));
        stat.setReceivingTd(nullIfZero(dto.getReceivingTd()));
        stat.setTargets(nullIfZero(dto.getTargets()));

        // Misc
        stat.setFumbles(nullIfZero(dto.getFumbles()));

        // Kicker
        stat.setPatMade(nullIfZero(dto.getPatMade()));
        stat.setPatMissed(nullIfZero(dto.getPatMissed()));
        stat.setFgMade0_19(nullIfZero(dto.getFgMade0_19()));
        stat.setFgMade20_29(nullIfZero(dto.getFgMade20_29()));
        stat.setFgMade30_39(nullIfZero(dto.getFgMade30_39()));
        stat.setFgMade40_49(nullIfZero(dto.getFgMade40_49()));
        stat.setFgMade50(nullIfZero(dto.getFgMade50()));
        stat.setFgMiss20_29(nullIfZero(dto.getFgMiss20_29()));
        stat.setFgMiss30_39(nullIfZero(dto.getFgMiss30_39()));

        playerStatRepository.save(stat);
        return isNew;
    }

    /**
     * Sort all PlayerStats for the season by totalPoints descending and
     * write an integer rank (1 = best) back to each row.
     */
    private int assignRanks(int year) {
        List<PlayerStat> allStats = playerStatRepository.findBySeasonOrderByRankAsc(year);

        // Re-sort by totalPoints desc (nulls last)
        allStats.sort(Comparator.comparingDouble(
                (PlayerStat s) -> s.getTotalPoints() != null ? s.getTotalPoints() : 0.0)
                .reversed());

        for (int i = 0; i < allStats.size(); i++) {
            allStats.get(i).setRank(i + 1);
        }
        playerStatRepository.saveAll(allStats);
        return allStats.size();
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static Integer nullIfZero(int v) {
        return v == 0 ? null : v;
    }

    private static Double roundTo2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
