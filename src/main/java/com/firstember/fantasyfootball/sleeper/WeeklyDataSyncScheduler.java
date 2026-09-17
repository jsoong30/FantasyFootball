package com.firstember.fantasyfootball.sleeper;

import com.firstember.fantasyfootball.config.SeasonConfig;
import com.firstember.fantasyfootball.ml.MlPredictionService;
import com.firstember.fantasyfootball.ml.TrainingDataExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Weekly automatic refresh of player stats/rankings, predictions, and training CSVs -- the P1
 * item in docs/week-by-week-plan.md ("Automation & data freshness") that {@link
 * LeagueSyncScheduler} didn't cover, since that one only re-syncs league standings/rosters, not
 * player stats. Default cron is Tuesday 6am, an hour ahead of the league sync (7am) -- there's no
 * ordering dependency between the two jobs, this just keeps the heavier one (18 Sleeper + 18 ESPN
 * calls, 1-3 min) from overlapping the league sync's window.
 * <p>
 * Three steps, each independent so one failing doesn't sink the others:
 * <ol>
 *   <li>{@code syncSeason(currentSeason)} -- the only path that recomputes {@code PlayerStat}
 *   totals, overall rank, and positional rank (see {@code SleeperService.assignRanks}). Every
 *   page that reads player stats (players list/detail, stats page, rankings, predictions) picks
 *   this up automatically since they all read the same persisted rows.</li>
 *   <li>Re-run predictions with no retrain -- the season model barely moves week to week, but
 *   Guardrails 3-5 (status discount, depth-chart nudge, ADP/FantasyPros market blend) use live
 *   data, so this keeps situational adjustments fresh. Skipped (not failed) if the Python ML
 *   server isn't reachable -- see the "Reliability caveat" in the plan doc: this job assumes the
 *   app is up, but not necessarily uvicorn too.</li>
 *   <li>Write both training CSVs to {@code ml/data/} so a retrain is always just
 *   {@code py train.py} -- deliberately does NOT retrain the model itself; eyeballing the
 *   walk-forward eval MAE before trusting a new model is a judgment step that stays manual.
 *   Note: these two CSVs are tracked in git (not gitignored, despite older comments saying
 *   otherwise), so every run of this job leaves the working tree with a real diff.</li>
 * </ol>
 * currentSeason comes from {@link SleeperService#currentNflState()} (live) with {@link
 * SeasonConfig#getTargetSeason()} as the fallback if that fetch fails -- this is the "every
 * scheduled job reads current season from here" consumer the plan doc's `/state/nfl` item called
 * for.
 */
@Component
public class WeeklyDataSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyDataSyncScheduler.class);

    private final SleeperService sleeperService;
    private final SeasonConfig seasonConfig;
    private final MlPredictionService mlPredictionService;
    private final TrainingDataExportService trainingDataExportService;

    @Value("${app.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    public WeeklyDataSyncScheduler(SleeperService sleeperService,
                                   SeasonConfig seasonConfig,
                                   MlPredictionService mlPredictionService,
                                   TrainingDataExportService trainingDataExportService) {
        this.sleeperService = sleeperService;
        this.seasonConfig = seasonConfig;
        this.mlPredictionService = mlPredictionService;
        this.trainingDataExportService = trainingDataExportService;
    }

    @Scheduled(cron = "${app.scheduler.stats-sync-cron:0 0 6 * * TUE}")
    public void scheduledSync() {
        if (!schedulerEnabled) {
            log.debug("app.scheduler.enabled=false -- skipping scheduled weekly data sync");
            return;
        }
        log.info("Scheduled weekly data sync starting...");
        log.info("Scheduled weekly data sync finished: {}", runNow());
    }

    /** Also the manual "Sync Player Data" admin button (POST /admin/sync-weekly-data). */
    public String runNow() {
        int currentSeason = resolveCurrentSeason();

        String statsResult;
        try {
            statsResult = sleeperService.syncSeason(currentSeason);
        } catch (Exception e) {
            log.warn("Weekly stats sync failed for season {}: {}", currentSeason, e.getMessage());
            statsResult = "Stats sync FAILED: " + e.getMessage();
        }

        String csvResult;
        try {
            trainingDataExportService.writeTrainingCsvsToDisk(Path.of("ml", "data"));
            csvResult = "CSVs written to ml/data/.";
        } catch (Exception e) {
            log.warn("Writing training CSVs failed: {}", e.getMessage());
            csvResult = "CSV write FAILED: " + e.getMessage();
        }

        String predictResult;
        if (!mlPredictionService.isModelConnected()) {
            predictResult = "Prediction refresh skipped -- ML server not reachable.";
        } else {
            try {
                predictResult = mlPredictionService.syncPredictions(
                        seasonConfig.getSourceSeason(), seasonConfig.getTargetSeason());
            } catch (Exception e) {
                log.warn("Weekly prediction refresh failed: {}", e.getMessage());
                predictResult = "Prediction refresh FAILED: " + e.getMessage();
            }
        }

        return String.format("[Stats] %s [CSVs] %s [Predictions] %s", statsResult, csvResult, predictResult);
    }

    /** Live current season from Sleeper, falling back to SeasonConfig's target season on failure. */
    private int resolveCurrentSeason() {
        try {
            SleeperNflStateDTO state = sleeperService.currentNflState();
            if (state != null && state.getSeason() != null) {
                return Integer.parseInt(state.getSeason());
            }
        } catch (Exception e) {
            log.warn("Could not resolve live current season from Sleeper, falling back to SeasonConfig: {}",
                    e.getMessage());
        }
        return seasonConfig.getTargetSeason();
    }
}
