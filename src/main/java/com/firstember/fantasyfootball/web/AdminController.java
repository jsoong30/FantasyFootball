package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.domain.Player;
import com.firstember.fantasyfootball.domain.PlayerStat;
import com.firstember.fantasyfootball.ml.ConsistencyStats;
import com.firstember.fantasyfootball.ml.MlPredictionService;
import com.firstember.fantasyfootball.repo.PlayerStatRepository;
import com.firstember.fantasyfootball.repo.PlayerWeeklyStatRepository;
import com.firstember.fantasyfootball.sleeper.SleeperService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final SleeperService sleeperService;
    private final MlPredictionService mlPredictionService;
    private final PlayerStatRepository playerStatRepository;
    private final PlayerWeeklyStatRepository weeklyStatRepository;

    public AdminController(SleeperService sleeperService,
                           MlPredictionService mlPredictionService,
                           PlayerStatRepository playerStatRepository,
                           PlayerWeeklyStatRepository weeklyStatRepository) {
        this.sleeperService = sleeperService;
        this.mlPredictionService = mlPredictionService;
        this.playerStatRepository = playerStatRepository;
        this.weeklyStatRepository = weeklyStatRepository;
    }

    /** Show the admin sync page. */
    @GetMapping("/sync")
    public String syncPage(Model model) {
        model.addAttribute("message", null);
        return "admin/sync";
    }

    /**
     * Trigger a Sleeper data sync for the given season year.
     * This can take 1–3 minutes (18 API calls + DB upserts).
     */
    @PostMapping("/sync")
    public String triggerSync(@RequestParam(defaultValue = "2024") int year, Model model) {
        try {
            String result = sleeperService.syncSeason(year);
            model.addAttribute("message", result);
            model.addAttribute("success", true);
        } catch (Exception e) {
            model.addAttribute("message", "Sync failed: " + e.getMessage());
            model.addAttribute("success", false);
        }
        return "admin/sync";
    }

    /**
     * Sync predictions from the Python ML API.
     * Uses {@code sourceSeason} stats to project {@code targetSeason} points.
     */
    @PostMapping("/predict")
    public String triggerPredict(
            @RequestParam(defaultValue = "2025") int sourceSeason,
            @RequestParam(defaultValue = "2026") int targetSeason,
            Model model) {
        try {
            String result = mlPredictionService.syncPredictions(sourceSeason, targetSeason);
            model.addAttribute("predictMessage", result);
            model.addAttribute("predictSuccess", true);
        } catch (Exception e) {
            model.addAttribute("predictMessage", "Prediction sync failed: " + e.getMessage());
            model.addAttribute("predictSuccess", false);
        }
        model.addAttribute("message", null);
        return "admin/sync";
    }

    /**
     * Backfill opponent_code on existing PlayerWeeklyStat rows for a given season
     * by fetching the NFL schedule from ESPN's public scoreboard API.
     * POST /admin/sync-opponents?year=2025
     */
    @PostMapping("/sync-opponents")
    public String syncOpponents(@RequestParam(defaultValue = "2025") int year, Model model) {
        try {
            String result = sleeperService.backfillOpponents(year);
            model.addAttribute("opponentMessage", result);
            model.addAttribute("opponentSuccess", true);
        } catch (Exception e) {
            model.addAttribute("opponentMessage", "Opponent sync failed: " + e.getMessage());
            model.addAttribute("opponentSuccess", false);
        }
        model.addAttribute("message", null);
        return "admin/sync";
    }

    /**
     * Export weekly player stats as a flat CSV for ML training (schedule-strength feature).
     * GET /admin/export/weekly → all seasons, all weeks
     * Columns: season, week, full_name, position, team_code, opponent_code, total_points
     */
    @GetMapping("/export/weekly")
    @ResponseBody
    public ResponseEntity<byte[]> exportWeeklyCsv() {
        List<Object[]> rows = weeklyStatRepository.findWeeklyExportData();

        StringBuilder csv = new StringBuilder();
        csv.append("season,week,full_name,position,team_code,opponent_code,total_points\n");

        for (Object[] row : rows) {
            csv.append(row[0]).append(',')                    // season
               .append(row[1]).append(',')                    // week
               .append(escape((String) row[2])).append(',')  // full_name
               .append(escape((String) row[3])).append(',')  // position
               .append(escape((String) row[4])).append(',')  // team_code
               .append(row[5] != null ? row[5] : "").append(',')  // opponent_code (nullable)
               .append(row[6] != null ? row[6] : "").append('\n'); // total_points
        }

        byte[] bytes = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"fantasy_weekly_all.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

    /**
     * Export player season stats as a flat CSV for ML training.
     * GET /admin/export          → all synced seasons
     * GET /admin/export?year=2024 → single season
     */
    @GetMapping("/export")
    @ResponseBody
    public ResponseEntity<byte[]> exportCsv(@RequestParam(required = false) Integer year) {
        // Build games_played map: "playerId_season" → count
        Map<String, Long> gamesMap = new HashMap<>();
        for (Object[] row : weeklyStatRepository.countWeeksByPlayerAndSeason()) {
            gamesMap.put(row[0] + "_" + row[1], (Long) row[2]);
        }

        // Build weekly points map: "playerId_season" → list of weekly scores
        Map<String, List<Double>> weeklyPtsMap = new HashMap<>();
        for (Object[] row : weeklyStatRepository.findAllWeeklyPoints()) {
            String key = row[0] + "_" + row[1];
            weeklyPtsMap.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(((Number) row[2]).doubleValue());
        }

        List<PlayerStat> stats;
        if (year != null) {
            stats = playerStatRepository.findBySeasonOrderByRankAsc(year);
        } else {
            stats = playerStatRepository.findAll();
            stats.sort(Comparator
                    .comparingInt((PlayerStat s) -> s.getSeason() != null ? s.getSeason() : 0)
                    .thenComparingInt(s -> s.getRank() != null ? s.getRank() : Integer.MAX_VALUE));
        }

        StringBuilder csv = new StringBuilder();
        csv.append("season,position,full_name,team,age,games_played,total_points,")
           .append("passing_yds,passing_td,passing_int,")
           .append("rushing_yds,rushing_td,")
           .append("targets,receiving_rec,receiving_yds,receiving_td,reception_pct,fumbles,")
           .append("pat_made,pat_missed,fg_made,")
           .append("def_sacks,def_ints,def_fum_rec,def_td,def_safeties,def_blocked_kicks,")
           .append("pts_allowed,yds_allowed,")
           .append("weekly_pts_std,floor,ceiling,games_over_10,games_over_20\n");

        for (PlayerStat s : stats) {
            Player p = s.getPlayer();
            long games = gamesMap.getOrDefault(p.getId() + "_" + s.getSeason(), 0L);

            double recPct = 0.0;
            if (s.getTargets() != null && s.getTargets() > 0 && s.getReceivingRec() != null) {
                recPct = Math.round(s.getReceivingRec() * 1000.0 / s.getTargets()) / 10.0;
            }

            List<Double> wkPts = weeklyPtsMap.getOrDefault(p.getId() + "_" + s.getSeason(), List.of());
            ConsistencyStats cs = ConsistencyStats.of(wkPts);

            csv.append(s.getSeason()).append(',')
               .append(escape(p.getPosition())).append(',')
               .append(escape(p.getFullName())).append(',')
               .append(escape(p.getTeam() != null ? p.getTeam().getCode() : "")).append(',')
               .append(p.getAge() != null ? p.getAge() : "").append(',')
               .append(games).append(',')
               .append(d(s.getTotalPoints())).append(',')
               .append(i(s.getPassingYds())).append(',')
               .append(i(s.getPassingTd())).append(',')
               .append(i(s.getPassingInt())).append(',')
               .append(i(s.getRushingYds())).append(',')
               .append(i(s.getRushingTd())).append(',')
               .append(i(s.getTargets())).append(',')
               .append(i(s.getReceivingRec())).append(',')
               .append(i(s.getReceivingYds())).append(',')
               .append(i(s.getReceivingTd())).append(',')
               .append(recPct).append(',')
               .append(i(s.getFumbles())).append(',')
               .append(i(s.getPatMade())).append(',')
               .append(i(s.getPatMissed())).append(',')
               .append(sum(s.getFgMade0_19(), s.getFgMade20_29(), s.getFgMade30_39(),
                           s.getFgMade40_49(), s.getFgMade50())).append(',')
               .append(i(s.getDefSacks())).append(',')
               .append(i(s.getDefInts())).append(',')
               .append(i(s.getDefFumRecoveries())).append(',')
               .append(i(s.getDefTd())).append(',')
               .append(i(s.getDefSafeties())).append(',')
               .append(i(s.getDefBlockedKicks())).append(',')
               .append(i(s.getPtsAllowed())).append(',')
               .append(i(s.getYdsAllowed())).append(',')
               .append(fmt2(cs.std)).append(',')
               .append(fmt2(cs.floor)).append(',')
               .append(fmt2(cs.ceiling)).append(',')
               .append(cs.gamesOver10).append(',')
               .append(cs.gamesOver20).append('\n');
        }

        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        String filename = year != null ? "fantasy_stats_" + year + ".csv" : "fantasy_stats_all.csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

    // ── CSV helpers ───────────────────────────────────────────────────────────

    private static String escape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private static int i(Integer v)   { return v != null ? v : 0; }
    private static double d(Double v)  { return v != null ? v : 0.0; }
    private static double fmt2(double v) { return Math.round(v * 100.0) / 100.0; }

    private static int sum(Integer... vals) {
        int total = 0;
        for (Integer v : vals) if (v != null) total += v;
        return total;
    }

}
