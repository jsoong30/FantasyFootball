package com.firstember.fantasyfootball.ml;

import com.firstember.fantasyfootball.domain.Player;
import com.firstember.fantasyfootball.domain.PlayerStat;
import com.firstember.fantasyfootball.repo.PlayerStatRepository;
import com.firstember.fantasyfootball.repo.PlayerWeeklyStatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the two flat CSVs {@code ml/train.py} trains on -- season stats and weekly stats (for
 * schedule-strength). Extracted out of {@code AdminController}'s {@code /admin/export} endpoints
 * so the exact same CSV-building logic can also be written straight to {@code ml/data/*.csv} by
 * the weekly sync job ({@link com.firstember.fantasyfootball.sleeper.WeeklyDataSyncScheduler}),
 * not just downloaded on demand. Column shapes here MUST stay in lockstep with what
 * {@code ml/train.py}/{@code ml/features.py} expect -- see CLAUDE.md "Admin Panel" steps 3-4.
 */
@Service
public class TrainingDataExportService {

    private static final Logger log = LoggerFactory.getLogger(TrainingDataExportService.class);

    private final PlayerStatRepository playerStatRepository;
    private final PlayerWeeklyStatRepository weeklyStatRepository;

    public TrainingDataExportService(PlayerStatRepository playerStatRepository,
                                     PlayerWeeklyStatRepository weeklyStatRepository) {
        this.playerStatRepository = playerStatRepository;
        this.weeklyStatRepository = weeklyStatRepository;
    }

    /** GET /admin/export/weekly body -- season, week, full_name, position, team_code, opponent_code, total_points. */
    public String weeklyStatsCsv() {
        List<Object[]> rows = weeklyStatRepository.findWeeklyExportData();

        StringBuilder csv = new StringBuilder();
        csv.append("season,week,full_name,position,team_code,opponent_code,total_points\n");

        for (Object[] row : rows) {
            csv.append(row[0]).append(',')                     // season
               .append(row[1]).append(',')                     // week
               .append(escape((String) row[2])).append(',')   // full_name
               .append(escape((String) row[3])).append(',')   // position
               .append(escape((String) row[4])).append(',')   // team_code
               .append(row[5] != null ? row[5] : "").append(',')   // opponent_code (nullable)
               .append(row[6] != null ? row[6] : "").append('\n'); // total_points
        }
        return csv.toString();
    }

    /** GET /admin/export body -- all seasons, or a single one if {@code year} is non-null. */
    public String seasonStatsCsv(Integer year) {
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
           .append("off_snaps,team_off_snaps,snap_pct,")
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

            double snapPct = 0.0;
            if (s.getTeamOffSnaps() != null && s.getTeamOffSnaps() > 0 && s.getOffSnaps() != null) {
                snapPct = Math.round(s.getOffSnaps() * 1000.0 / s.getTeamOffSnaps()) / 10.0;
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
               .append(i(s.getOffSnaps())).append(',')
               .append(i(s.getTeamOffSnaps())).append(',')
               .append(snapPct).append(',')
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
        return csv.toString();
    }

    /**
     * Writes both training CSVs to {@code ml/data/} (relative to the app's working directory,
     * same location the admin-panel downloads are meant to be saved to per CLAUDE.md). Used by
     * the weekly sync job so a retrain is always just {@code py train.py} -- never auto-retrains
     * the model itself, that's still a deliberate manual step (see docs/week-by-week-plan.md).
     */
    public void writeTrainingCsvsToDisk(Path mlDataDir) throws IOException {
        Files.createDirectories(mlDataDir);
        Path statsPath = mlDataDir.resolve("fantasy_stats_all.csv");
        Path weeklyPath = mlDataDir.resolve("fantasy_weekly_all.csv");
        Files.writeString(statsPath, seasonStatsCsv(null), StandardCharsets.UTF_8);
        Files.writeString(weeklyPath, weeklyStatsCsv(), StandardCharsets.UTF_8);
        log.info("Wrote training CSVs to {} and {}", statsPath, weeklyPath);
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
