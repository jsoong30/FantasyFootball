package com.firstember.fantasyfootball.config;

import com.firstember.fantasyfootball.domain.Player;
import com.firstember.fantasyfootball.domain.Team;
import com.firstember.fantasyfootball.repo.PlayerRepository;
import com.firstember.fantasyfootball.repo.TeamRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DataLoader implements ApplicationRunner {

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

    public DataLoader(TeamRepository teamRepository, PlayerRepository playerRepository) {
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (teamRepository.count() == 0) {
            loadTeams();
        }
        if (playerRepository.count() == 0) {
            loadPlayers();
        }
    }

    private void loadTeams() throws Exception {
        List<Team> teams = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource("data/teams.csv").getInputStream()))) {

            reader.readLine(); // skip header: team_code,team_name

            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", -1);
                if (parts.length == 0 || parts[0].isBlank()) continue;

                String code = parts[0].trim();
                String name = (parts.length > 1 && !parts[1].isBlank())
                        ? parts[1].trim()
                        : TEAM_NAMES.getOrDefault(code, code);

                Team team = new Team();
                team.setCode(code);
                team.setName(name);
                teams.add(team);
            }
        }

        teamRepository.saveAll(teams);
    }

    private void loadPlayers() throws Exception {
        Map<String, Team> teamsByCode = new HashMap<>();
        teamRepository.findAllByOrderByCodeAsc().forEach(t -> teamsByCode.put(t.getCode(), t));

        List<Player> players = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource("data/players_core.csv").getInputStream()))) {

            reader.readLine(); // skip header: player_id,full_name,position,team_code,season,bye_week,depth,age,status

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
                player.setTeam(teamsByCode.get(p.length > 3 ? p[3].trim() : ""));
                player.setSeason(parseIntOrNull(p.length > 4 ? p[4] : ""));
                player.setByeWeek(parseIntOrNull(p.length > 5 ? p[5] : ""));
                player.setDepth(blankToNull(p.length > 6 ? p[6] : ""));
                player.setAge(parseIntOrNull(p.length > 7 ? p[7] : ""));
                player.setStatus(blankToNull(p.length > 8 ? p[8] : ""));
                players.add(player);
            }
        }

        playerRepository.saveAll(players);
    }

    private Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
