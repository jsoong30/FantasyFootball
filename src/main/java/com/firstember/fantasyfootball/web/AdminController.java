package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.config.SeasonConfig;
import com.firstember.fantasyfootball.ml.MlPredictionService;
import com.firstember.fantasyfootball.ml.TrainingDataExportService;
import com.firstember.fantasyfootball.sleeper.LeagueSyncScheduler;
import com.firstember.fantasyfootball.sleeper.SleeperService;
import com.firstember.fantasyfootball.sleeper.WeeklyDataSyncScheduler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final SeasonConfig seasonConfig;
    private final SleeperService sleeperService;
    private final MlPredictionService mlPredictionService;
    private final TrainingDataExportService trainingDataExportService;
    private final LeagueSyncScheduler leagueSyncScheduler;
    private final WeeklyDataSyncScheduler weeklyDataSyncScheduler;

    public AdminController(SeasonConfig seasonConfig,
                           SleeperService sleeperService,
                           MlPredictionService mlPredictionService,
                           TrainingDataExportService trainingDataExportService,
                           LeagueSyncScheduler leagueSyncScheduler,
                           WeeklyDataSyncScheduler weeklyDataSyncScheduler) {
        this.seasonConfig = seasonConfig;
        this.sleeperService = sleeperService;
        this.mlPredictionService = mlPredictionService;
        this.trainingDataExportService = trainingDataExportService;
        this.leagueSyncScheduler = leagueSyncScheduler;
        this.weeklyDataSyncScheduler = weeklyDataSyncScheduler;
    }

    /** Show the admin sync page. */
    @GetMapping("/sync")
    public String syncPage(Model model) {
        model.addAttribute("message", null);
        model.addAttribute("nflState", sleeperService.currentNflState());
        model.addAttribute("sourceSeason", seasonConfig.getSourceSeason());
        model.addAttribute("targetSeason", seasonConfig.getTargetSeason());
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
     * Sync a single week of stats instead of the full 18-week season — for in-season refreshes,
     * or a "pre" seasonType dry run against preseason data before the regular season starts.
     * POST /admin/sync-week?year=2026&week=1&seasonType=regular
     */
    @PostMapping("/sync-week")
    public String syncWeek(@RequestParam(defaultValue = "2026") int year,
                           @RequestParam(defaultValue = "1") int week,
                           @RequestParam(defaultValue = "regular") String seasonType,
                           Model model) {
        try {
            String result = sleeperService.syncWeek(year, week, seasonType);
            model.addAttribute("weekMessage", result);
            model.addAttribute("weekSuccess", true);
        } catch (Exception e) {
            model.addAttribute("weekMessage", "Week sync failed: " + e.getMessage());
            model.addAttribute("weekSuccess", false);
        }
        model.addAttribute("message", null);
        return "admin/sync";
    }

    /**
     * Manually trigger the same league re-sync {@link LeagueSyncScheduler} runs weekly — every
     * added league across every user: standings, scoring, rosters, status.
     * POST /admin/sync-leagues
     */
    @PostMapping("/sync-leagues")
    public String syncLeagues(Model model) {
        try {
            String result = leagueSyncScheduler.syncAllLeaguesNow();
            model.addAttribute("leagueSyncMessage", result);
            model.addAttribute("leagueSyncSuccess", true);
        } catch (Exception e) {
            model.addAttribute("leagueSyncMessage", "League sync failed: " + e.getMessage());
            model.addAttribute("leagueSyncSuccess", false);
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
     * Manually trigger the same full player-stats sync {@link WeeklyDataSyncScheduler} runs
     * weekly — syncSeason(currentSeason), weekly re-predict, and CSV export to ml/data/.
     * POST /admin/sync-weekly-data
     */
    @PostMapping("/sync-weekly-data")
    public String syncWeeklyData(Model model) {
        try {
            String result = weeklyDataSyncScheduler.runNow();
            model.addAttribute("weeklyDataMessage", result);
            model.addAttribute("weeklyDataSuccess", true);
        } catch (Exception e) {
            model.addAttribute("weeklyDataMessage", "Weekly data sync failed: " + e.getMessage());
            model.addAttribute("weeklyDataSuccess", false);
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
        byte[] bytes = trainingDataExportService.weeklyStatsCsv().getBytes(StandardCharsets.UTF_8);
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
        byte[] bytes = trainingDataExportService.seasonStatsCsv(year).getBytes(StandardCharsets.UTF_8);
        String filename = year != null ? "fantasy_stats_" + year + ".csv" : "fantasy_stats_all.csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

}
