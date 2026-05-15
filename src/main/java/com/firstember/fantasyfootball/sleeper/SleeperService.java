package com.firstember.fantasyfootball.sleeper;

import com.firstember.fantasyfootball.domain.Player;
import com.firstember.fantasyfootball.domain.PlayerStat;
import com.firstember.fantasyfootball.domain.PlayerWeeklyStat;
import com.firstember.fantasyfootball.domain.Team;
import com.firstember.fantasyfootball.repo.PlayerRepository;
import com.firstember.fantasyfootball.repo.PlayerStatRepository;
import com.firstember.fantasyfootball.repo.PlayerWeeklyStatRepository;
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

    /** Sleeper positions we care about. Sleeper calls team defenses "DEF"; we store them as "DST". */
    private static final Set<String> KEEP_POSITIONS = Set.of("QB", "RB", "WR", "TE", "K", "DEF");

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
    private final PlayerWeeklyStatRepository weeklyStatRepository;
    private final TeamRepository teamRepository;

    public SleeperService(RestTemplateBuilder builder,
                          PlayerRepository playerRepository,
                          PlayerStatRepository playerStatRepository,
                          PlayerWeeklyStatRepository weeklyStatRepository,
                          TeamRepository teamRepository) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(90))
                .build();
        this.playerRepository     = playerRepository;
        this.playerStatRepository  = playerStatRepository;
        this.weeklyStatRepository  = weeklyStatRepository;
        this.teamRepository       = teamRepository;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @Transactional(timeout = 600)
    public String syncSeason(int year) {
        log.info("=== Sleeper sync starting — season {} ===", year);

        // 1) Fetch master player registry
        Map<String, SleeperPlayerDTO> sleeperPlayers = fetchAllPlayers();
        log.info("Fetched {} total players from Sleeper", sleeperPlayers.size());

        // 2) Fetch all 18 weeks of raw stat maps in one pass
        Map<Integer, Map<String, Map<String, Object>>> allWeeklyRaw = fetchAllWeeklyRaw(year);
        log.info("Fetched {} weeks of stats", allWeeklyRaw.size());

        // 3) Accumulate season totals from the raw weekly data
        Map<String, SleeperStatsDTO> seasonStats = accumulateFromRaw(allWeeklyRaw);

        // 4) Build team lookup
        Map<String, Team> teamByCode = new HashMap<>();
        teamRepository.findAll().forEach(t -> teamByCode.put(t.getCode(), t));

        // 5) Upsert players + season totals; track which players were saved
        int created = 0, updated = 0, skipped = 0;
        Map<String, Player> savedPlayers = new HashMap<>(); // sleeperPlayerId → Player

        for (Map.Entry<String, SleeperStatsDTO> entry : seasonStats.entrySet()) {
            String sleeperPlayerId = entry.getKey();
            SleeperStatsDTO stats  = entry.getValue();

            if (!stats.hasStats()) { skipped++; continue; }

            SleeperPlayerDTO playerDTO = sleeperPlayers.get(sleeperPlayerId);
            if (playerDTO == null) { skipped++; continue; }

            String position = playerDTO.getPosition();
            if (position == null || !KEEP_POSITIONS.contains(position)) { skipped++; continue; }

            String fullName = playerDTO.getFullName();
            if ((fullName == null || fullName.isBlank()) && !"DEF".equals(position)) {
                skipped++; continue;
            }

            try {
                Player player = upsertPlayer(playerDTO, sleeperPlayerId, year, teamByCode);
                boolean wasNew = upsertStat(player, stats, year);
                savedPlayers.put(sleeperPlayerId, player);
                if (wasNew) created++; else updated++;
            } catch (Exception e) {
                log.warn("Skipping player {} ({}): {}", sleeperPlayerId, position, e.getMessage());
                skipped++;
            }
        }
        log.info("Season totals — created={}, updated={}, skipped={}", created, updated, skipped);

        // 6) Save per-week stats for every saved player
        int weeklySaved = saveWeeklyStats(year, allWeeklyRaw, savedPlayers);
        log.info("Saved {} player-week records", weeklySaved);

        // 7) Recalculate overall ranks
        int ranked = assignRanks(year);
        log.info("Assigned ranks to {} players for season {}", ranked, year);

        return String.format(
                "Season %d sync complete — %d players created, %d updated, %d weekly records saved, %d ranked.",
                year, created, updated, weeklySaved, ranked);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** GET /players/nfl → Map<sleeper_player_id, SleeperPlayerDTO> */
    private Map<String, SleeperPlayerDTO> fetchAllPlayers() {
        String url = BASE + "/players/nfl";
        Map<String, SleeperPlayerDTO> result =
                restTemplate.exchange(url, HttpMethod.GET, null,
                        new ParameterizedTypeReference<Map<String, SleeperPlayerDTO>>() {})
                        .getBody();
        return result != null ? result : Collections.emptyMap();
    }

    /**
     * Fetch all regular-season weeks in one pass.
     * Returns Map&lt;week, Map&lt;playerId, statMap&gt;&gt;
     */
    private Map<Integer, Map<String, Map<String, Object>>> fetchAllWeeklyRaw(int year) {
        Map<Integer, Map<String, Map<String, Object>>> allWeeks = new LinkedHashMap<>();
        for (int week = 1; week <= REGULAR_SEASON_WEEKS; week++) {
            String url = BASE + "/stats/nfl/regular/" + year + "/" + week;
            try {
                Map<String, Map<String, Object>> weekData =
                        restTemplate.exchange(url, HttpMethod.GET, null,
                                new ParameterizedTypeReference<Map<String, Map<String, Object>>>() {})
                                .getBody();
                if (weekData != null) {
                    allWeeks.put(week, weekData);
                    log.debug("Week {} — {} players", week, weekData.size());
                }
            } catch (Exception e) {
                log.warn("Failed to fetch week {} for {}: {}", week, year, e.getMessage());
            }
        }
        return allWeeks;
    }

    /** Accumulate season totals from pre-fetched weekly raw data. */
    private Map<String, SleeperStatsDTO> accumulateFromRaw(
            Map<Integer, Map<String, Map<String, Object>>> allWeeks) {
        Map<String, SleeperStatsDTO> totals = new HashMap<>();
        allWeeks.values().forEach(weekData ->
                weekData.forEach((playerId, statMap) ->
                        totals.computeIfAbsent(playerId, k -> new SleeperStatsDTO())
                              .addWeek(statMap)));
        return totals;
    }

    /**
     * Save one PlayerWeeklyStat per (player, week) for every player we successfully saved.
     * Batch-loads all existing records upfront to avoid per-row queries and duplicate-key errors
     * when re-syncing a season that was already loaded.
     */
    private int saveWeeklyStats(int year,
                                Map<Integer, Map<String, Map<String, Object>>> allWeeklyRaw,
                                Map<String, Player> savedPlayers) {

        // Build reverse map: DB player id → sleeper player id
        Map<Long, String> idToSleeper = new HashMap<>();
        savedPlayers.forEach((sleeperId, player) -> idToSleeper.put(player.getId(), sleeperId));

        // Batch-load ALL existing weekly records for these players in one query
        // Key: "playerId_week" → existing entity (so we update instead of insert)
        Set<Long> playerIds = new HashSet<>(idToSleeper.keySet());
        Map<String, PlayerWeeklyStat> existing = new HashMap<>();
        if (!playerIds.isEmpty()) {
            weeklyStatRepository.findByPlayer_IdInAndSeason(playerIds, year)
                    .forEach(w -> existing.put(w.getPlayer().getId() + "_" + w.getWeek(), w));
        }
        log.info("Loaded {} existing weekly stat records for upsert", existing.size());

        int count = 0;
        for (Map.Entry<Integer, Map<String, Map<String, Object>>> weekEntry : allWeeklyRaw.entrySet()) {
            int week = weekEntry.getKey();
            List<PlayerWeeklyStat> toSave = new ArrayList<>();

            for (Map.Entry<String, Map<String, Object>> playerEntry : weekEntry.getValue().entrySet()) {
                Player player = savedPlayers.get(playerEntry.getKey());
                if (player == null) continue;

                Map<String, Object> raw = playerEntry.getValue();
                double pts = asDouble(raw, "pts_ppr");
                if (pts == 0) continue; // didn't play this week

                // Find existing record or create new — no individual DB query needed
                String key = player.getId() + "_" + week;
                PlayerWeeklyStat w = existing.getOrDefault(key, new PlayerWeeklyStat());

                w.setPlayer(player);
                w.setSeason(year);
                w.setWeek(week);
                w.setTotalPoints(roundTo2(pts));
                w.setPassingYds(asNullableInt(raw, "pass_yd"));
                w.setPassingTd(asNullableInt(raw, "pass_td"));
                w.setPassingInt(asNullableInt(raw, "pass_int"));
                w.setRushingYds(asNullableInt(raw, "rush_yd"));
                w.setRushingTd(asNullableInt(raw, "rush_td"));
                w.setReceivingRec(asNullableInt(raw, "rec"));
                w.setReceivingYds(asNullableInt(raw, "rec_yd"));
                w.setReceivingTd(asNullableInt(raw, "rec_td"));
                w.setTargets(asNullableInt(raw, "rec_tgt"));
                w.setFumbles(asNullableInt(raw, "fum_lost"));
                w.setPatMade(asNullableInt(raw, "xpm"));
                w.setPatMissed(asNullableInt(raw, "xpmiss"));
                int fgMade = asInt(raw, "fgm_0_19") + asInt(raw, "fgm_20_29")
                           + asInt(raw, "fgm_30_39") + asInt(raw, "fgm_40_49")
                           + asInt(raw, "fgm_50p");
                w.setFgMade(fgMade > 0 ? fgMade : null);
                w.setDefSacks(asNullableInt(raw, "sack"));
                w.setDefInts(asNullableInt(raw, "int"));
                w.setDefFumRecoveries(asNullableInt(raw, "fum_rec"));
                w.setDefTd(asNullableInt(raw, "def_td"));
                w.setDefSafeties(asNullableInt(raw, "safe"));
                w.setDefBlockedKicks(asNullableInt(raw, "blk_kick"));
                w.setPtsAllowed(asNullableInt(raw, "pts_allow"));

                toSave.add(w);
            }

            weeklyStatRepository.saveAll(toSave);
            count += toSave.size();
        }
        return count;
    }

    private Player upsertPlayer(SleeperPlayerDTO dto, String extId, int year,
                                Map<String, Team> teamByCode) {
        String rawTeam  = dto.getTeam();
        String teamCode = rawTeam != null ? TEAM_CODE_FIX.getOrDefault(rawTeam, rawTeam) : null;
        Team   team     = teamCode != null ? teamByCode.get(teamCode) : null;

        String position = "DEF".equals(dto.getPosition()) ? "DST" : dto.getPosition();

        String fullName = dto.getFullName();
        if (fullName == null || fullName.isBlank()) {
            fullName = (teamCode != null ? teamCode : extId) + " Defense";
        }

        Optional<Player> found = playerRepository.findByExternalIdAndSeason(extId, year);
        if (found.isEmpty()) {
            found = playerRepository.findFirstByFullNameAndPositionAndSeason(fullName, position, year);
        }

        Player player = found.orElse(new Player());
        player.setExternalId(extId);
        player.setFullName(fullName);
        player.setPosition(position);
        player.setTeam(team);
        player.setSeason(year);
        player.setAge(dto.getAge());
        player.setStatus(dto.getStatus());

        return playerRepository.save(player);
    }

    private boolean upsertStat(Player player, SleeperStatsDTO dto, int year) {
        Optional<PlayerStat> existing = playerStatRepository.findByPlayer_IdAndSeason(player.getId(), year);
        boolean isNew = existing.isEmpty();
        PlayerStat stat = existing.orElse(new PlayerStat());

        stat.setPlayer(player);
        stat.setSeason(year);
        stat.setTotalPoints(dto.getTotalPoints() > 0 ? roundTo2(dto.getTotalPoints()) : null);
        stat.setPassingYds(nullIfZero(dto.getPassingYds()));
        stat.setPassingTd(nullIfZero(dto.getPassingTd()));
        stat.setPassingInt(nullIfZero(dto.getPassingInt()));
        stat.setRushingYds(nullIfZero(dto.getRushingYds()));
        stat.setRushingTd(nullIfZero(dto.getRushingTd()));
        stat.setReceivingRec(nullIfZero(dto.getReceivingRec()));
        stat.setReceivingYds(nullIfZero(dto.getReceivingYds()));
        stat.setReceivingTd(nullIfZero(dto.getReceivingTd()));
        stat.setTargets(nullIfZero(dto.getTargets()));
        stat.setFumbles(nullIfZero(dto.getFumbles()));
        stat.setPatMade(nullIfZero(dto.getPatMade()));
        stat.setPatMissed(nullIfZero(dto.getPatMissed()));
        stat.setFgMade0_19(nullIfZero(dto.getFgMade0_19()));
        stat.setFgMade20_29(nullIfZero(dto.getFgMade20_29()));
        stat.setFgMade30_39(nullIfZero(dto.getFgMade30_39()));
        stat.setFgMade40_49(nullIfZero(dto.getFgMade40_49()));
        stat.setFgMade50(nullIfZero(dto.getFgMade50()));
        stat.setFgMiss20_29(nullIfZero(dto.getFgMiss20_29()));
        stat.setFgMiss30_39(nullIfZero(dto.getFgMiss30_39()));
        stat.setDefSacks(nullIfZero(dto.getDefSacks()));
        stat.setDefInts(nullIfZero(dto.getDefInts()));
        stat.setDefFumRecoveries(nullIfZero(dto.getDefFumRecoveries()));
        stat.setDefTd(nullIfZero(dto.getDefTd()));
        stat.setDefSafeties(nullIfZero(dto.getDefSafeties()));
        stat.setDefBlockedKicks(nullIfZero(dto.getDefBlockedKicks()));
        stat.setPtsAllowed(nullIfZero(dto.getPtsAllowed()));
        stat.setYdsAllowed(nullIfZero(dto.getYdsAllowed()));

        playerStatRepository.save(stat);
        return isNew;
    }

    private int assignRanks(int year) {
        List<PlayerStat> allStats = playerStatRepository.findBySeasonOrderByRankAsc(year);
        allStats.sort(Comparator.comparingDouble(
                (PlayerStat s) -> s.getTotalPoints() != null ? s.getTotalPoints() : 0.0)
                .reversed());
        for (int i = 0; i < allStats.size(); i++) allStats.get(i).setRank(i + 1);
        playerStatRepository.saveAll(allStats);
        return allStats.size();
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static double asDouble(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : 0.0;
    }

    private static int asInt(Map<String, Object> map, String key) {
        return (int) Math.round(asDouble(map, key));
    }

    private static Integer asNullableInt(Map<String, Object> map, String key) {
        int v = asInt(map, key);
        return v == 0 ? null : v;
    }

    private static Integer nullIfZero(int v) {
        return v == 0 ? null : v;
    }

    private static Double roundTo2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
