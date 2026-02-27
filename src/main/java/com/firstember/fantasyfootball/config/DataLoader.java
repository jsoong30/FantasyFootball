package com.firstember.fantasyfootball.config;

import com.firstember.fantasyfootball.domain.Player;
import com.firstember.fantasyfootball.domain.PlayerStat;
import com.firstember.fantasyfootball.domain.Team;
import com.firstember.fantasyfootball.repo.PlayerRepository;
import com.firstember.fantasyfootball.repo.PlayerStatRepository;
import com.firstember.fantasyfootball.repo.TeamRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

@Component
public class DataLoader implements ApplicationRunner {

    private static final int SEASON = 2024;

    private static final Map<String, String> TEAM_NAMES = Map.ofEntries(
            Map.entry("ARI", "Cardinals"),
            Map.entry("ATL", "Falcons"),
            Map.entry("BAL", "Ravens"),
            Map.entry("BUF", "Bills"),
            Map.entry("CAR", "Panthers"),
            Map.entry("CHI", "Bears"),
            Map.entry("CIN", "Bengals"),
            Map.entry("CLE", "Browns"),
            Map.entry("DAL", "Cowboys"),
            Map.entry("DEN", "Broncos"),
            Map.entry("DET", "Lions"),
            Map.entry("FA",  "Free Agent"),
            Map.entry("GB",  "Packers"),
            Map.entry("HOU", "Texans"),
            Map.entry("IND", "Colts"),
            Map.entry("JAX", "Jaguars"),
            Map.entry("KC",  "Chiefs"),
            Map.entry("LAC", "Chargers"),
            Map.entry("LAR", "Rams"),
            Map.entry("LV",  "Raiders"),
            Map.entry("MIA", "Dolphins"),
            Map.entry("MIN", "Vikings"),
            Map.entry("NE",  "Patriots"),
            Map.entry("NO",  "Saints"),
            Map.entry("NYG", "Giants"),
            Map.entry("NYJ", "Jets"),
            Map.entry("PHI", "Eagles"),
            Map.entry("PIT", "Steelers"),
            Map.entry("SEA", "Seahawks"),
            Map.entry("SF",  "49ers"),
            Map.entry("TB",  "Buccaneers"),
            Map.entry("TEN", "Titans"),
            Map.entry("WAS", "Commanders")
    );

    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final PlayerStatRepository playerStatRepository;

    public DataLoader(TeamRepository teamRepository,
                      PlayerRepository playerRepository,
                      PlayerStatRepository playerStatRepository) {
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.playerStatRepository = playerStatRepository;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (teamRepository.count() == 0)       loadTeams();
        if (playerRepository.count() == 0)     loadPlayers();
        if (playerStatRepository.count() == 0) loadAllStats();
    }

    // ── Teams ────────────────────────────────────────────────────────────────

    private void loadTeams() throws Exception {
        List<Team> teams = new ArrayList<>();
        try (BufferedReader reader = buffered("data/teams.csv")) {
            reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split(",", -1);
                if (p.length == 0 || p[0].isBlank()) continue;
                String code = p[0].trim();
                String name = (p.length > 1 && !p[1].isBlank())
                        ? p[1].trim() : TEAM_NAMES.getOrDefault(code, code);
                Team t = new Team(); t.setCode(code); t.setName(name);
                teams.add(t);
            }
        }
        teamRepository.saveAll(teams);
    }

    // ── Players ──────────────────────────────────────────────────────────────

    private void loadPlayers() throws Exception {
        Map<String, Team> byCode = new HashMap<>();
        teamRepository.findAllByOrderByCodeAsc().forEach(t -> byCode.put(t.getCode(), t));

        List<Player> players = new ArrayList<>();
        try (BufferedReader reader = buffered("data/players_core.csv")) {
            reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split(",", -1);
                if (p.length < 3) continue;
                String fullName = p[1].trim();
                String position = p[2].trim();
                if (fullName.isBlank() || position.isBlank()) continue;

                Player player = new Player();
                player.setExternalId(blankToNull(p[0]));
                player.setFullName(fullName);
                player.setPosition(position);
                player.setTeam(byCode.get(p.length > 3 ? p[3].trim() : ""));
                player.setSeason(parseIntOrNull(p.length > 4  ? p[4]  : ""));
                player.setByeWeek(parseIntOrNull(p.length > 5 ? p[5]  : ""));
                player.setDepth(blankToNull(p.length > 6      ? p[6]  : ""));
                player.setAge(parseIntOrNull(p.length > 7     ? p[7]  : ""));
                player.setStatus(blankToNull(p.length > 8     ? p[8]  : ""));
                players.add(player);
            }
        }
        playerRepository.saveAll(players);
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    private void loadAllStats() throws Exception {
        // Build lookup: externalId -> Player
        Map<String, Player> byExtId = new HashMap<>();
        playerRepository.findAllByOrderByFullNameAsc()
                .forEach(p -> { if (p.getExternalId() != null) byExtId.put(p.getExternalId(), p); });

        List<PlayerStat> stats = new ArrayList<>();
        stats.addAll(loadStatFile("data/QB_season.csv", byExtId));
        stats.addAll(loadStatFile("data/RB_season.csv", byExtId));
        stats.addAll(loadStatFile("data/WR_season.csv", byExtId));
        stats.addAll(loadStatFile("data/TE_season.csv", byExtId));
        stats.addAll(loadKickerStatFile("data/K_season.csv", byExtId));
        playerStatRepository.saveAll(stats);
    }

    /** QB / RB / WR / TE share the same column layout. */
    private List<PlayerStat> loadStatFile(String path, Map<String, Player> byExtId) throws Exception {
        List<PlayerStat> stats = new ArrayList<>();
        try (BufferedReader reader = buffered(path)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return stats;
            Map<String, Integer> col = headerIndex(headerLine);

            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = csvSplit(line, col.size());
                String playerId = get(p, col, "PlayerId");
                if (playerId == null) continue;
                Player player = byExtId.get(playerId);
                if (player == null) continue;  // not in our roster — skip

                PlayerStat s = new PlayerStat();
                s.setPlayer(player);
                s.setSeason(SEASON);
                s.setRank(parseIntOrNull(get(p, col, "Rank")));
                s.setTotalPoints(parseDblOrNull(get(p, col, "TotalPoints")));
                s.setPassingYds(parseIntOrNull(get(p, col, "PassingYDS")));
                s.setPassingTd(parseIntOrNull(get(p, col, "PassingTD")));
                s.setPassingInt(parseIntOrNull(get(p, col, "PassingInt")));
                s.setRushingYds(parseIntOrNull(get(p, col, "RushingYDS")));
                s.setRushingTd(parseIntOrNull(get(p, col, "RushingTD")));
                s.setReceivingRec(parseIntOrNull(get(p, col, "ReceivingRec")));
                s.setReceivingYds(parseIntOrNull(get(p, col, "ReceivingYDS")));
                s.setReceivingTd(parseIntOrNull(get(p, col, "ReceivingTD")));
                s.setTargets(parseIntOrNull(get(p, col, "Targets")));
                s.setFumbles(parseIntOrNull(get(p, col, "Fum")));
                s.setReceptionPct(parseDblOrNull(get(p, col, "ReceptionPercentage")));
                stats.add(s);
            }
        }
        return stats;
    }

    private List<PlayerStat> loadKickerStatFile(String path, Map<String, Player> byExtId) throws Exception {
        List<PlayerStat> stats = new ArrayList<>();
        try (BufferedReader reader = buffered(path)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return stats;
            Map<String, Integer> col = headerIndex(headerLine);

            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = csvSplit(line, col.size());
                String playerId = get(p, col, "PlayerId");
                if (playerId == null) continue;
                Player player = byExtId.get(playerId);
                if (player == null) continue;

                PlayerStat s = new PlayerStat();
                s.setPlayer(player);
                s.setSeason(SEASON);
                s.setRank(parseIntOrNull(get(p, col, "Rank")));
                s.setTotalPoints(parseDblOrNull(get(p, col, "TotalPoints")));
                s.setPatMade(parseIntOrNull(get(p, col, "PatMade")));
                s.setPatMissed(parseIntOrNull(get(p, col, "PatMissed")));
                s.setFgMade0_19(parseIntOrNull(get(p, col, "FgMade_0-19")));
                s.setFgMade20_29(parseIntOrNull(get(p, col, "FgMade_20-29")));
                s.setFgMade30_39(parseIntOrNull(get(p, col, "FgMade_30-39")));
                s.setFgMade40_49(parseIntOrNull(get(p, col, "FgMade_40-49")));
                s.setFgMade50(parseIntOrNull(get(p, col, "FgMade_50")));
                s.setFgMiss20_29(parseIntOrNull(get(p, col, "FgMiss_20-29")));
                s.setFgMiss30_39(parseIntOrNull(get(p, col, "FgMiss_30-39")));
                stats.add(s);
            }
        }
        return stats;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BufferedReader buffered(String path) throws Exception {
        return new BufferedReader(new InputStreamReader(
                new ClassPathResource(path).getInputStream()));
    }

    private Map<String, Integer> headerIndex(String headerLine) {
        String[] headers = headerLine.split(",", -1);
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headers.length; i++) map.put(headers[i].trim(), i);
        return map;
    }

    private String[] csvSplit(String line, int expectedCols) {
        return line.split(",", expectedCols + 1);
    }

    private String get(String[] parts, Map<String, Integer> col, String name) {
        Integer idx = col.get(name);
        if (idx == null || idx >= parts.length) return null;
        String v = parts[idx].trim();
        return v.isEmpty() ? null : v;
    }

    private Integer parseIntOrNull(String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return null; }
    }

    private Double parseDblOrNull(String s) {
        if (s == null) return null;
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { return null; }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
