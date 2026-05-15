package com.firstember.fantasyfootball.config;

import com.firstember.fantasyfootball.domain.Team;
import com.firstember.fantasyfootball.repo.TeamRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
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

    public DataLoader(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Teams are seeded from CSV on first run.
        // Player + stat data is loaded via the Sleeper API — trigger it at /admin/sync.
        if (teamRepository.count() == 0) loadTeams();
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BufferedReader buffered(String path) throws Exception {
        return new BufferedReader(new InputStreamReader(
                new ClassPathResource(path).getInputStream()));
    }
}
