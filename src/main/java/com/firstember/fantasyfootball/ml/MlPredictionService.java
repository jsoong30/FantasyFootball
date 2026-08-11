package com.firstember.fantasyfootball.ml;

import com.firstember.fantasyfootball.domain.Player;
import com.firstember.fantasyfootball.domain.PlayerPrediction;
import com.firstember.fantasyfootball.domain.PlayerStat;
import com.firstember.fantasyfootball.repo.PlayerPredictionRepository;
import com.firstember.fantasyfootball.repo.PlayerStatRepository;
import com.firstember.fantasyfootball.repo.PlayerWeeklyStatRepository;
import com.firstember.fantasyfootball.sleeper.SleeperService;
import com.firstember.fantasyfootball.external.FantasyCalculatorService;
import com.firstember.fantasyfootball.external.FantasyProsService;
import com.firstember.fantasyfootball.external.NameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;


@Service
public class MlPredictionService {

    private static final Logger log = LoggerFactory.getLogger(MlPredictionService.class);

    private final RestTemplate restTemplate;
    private final PlayerStatRepository playerStatRepository;
    private final PlayerWeeklyStatRepository weeklyStatRepository;
    private final PlayerPredictionRepository predictionRepository;
    private final SleeperService sleeperService;
    private final FantasyCalculatorService fantasyCalculatorService;
    private final FantasyProsService fantasyProsService;

    @Value("${ml.api.url:http://localhost:8000}")
    private String mlApiUrl;

    public MlPredictionService(RestTemplateBuilder builder,
                               PlayerStatRepository playerStatRepository,
                               PlayerWeeklyStatRepository weeklyStatRepository,
                               PlayerPredictionRepository predictionRepository,
                               SleeperService sleeperService,
                               FantasyCalculatorService fantasyCalculatorService,
                               FantasyProsService fantasyProsService) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30))
                .build();
        this.playerStatRepository = playerStatRepository;
        this.weeklyStatRepository = weeklyStatRepository;
        this.predictionRepository = predictionRepository;
        this.sleeperService = sleeperService;
        this.fantasyCalculatorService = fantasyCalculatorService;
        this.fantasyProsService = fantasyProsService;
    }

    /** Returns true if the Python ML server is reachable and has at least one model loaded. */
    public boolean isModelConnected() {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(mlApiUrl + "/health", Map.class);
            List<?> loaded = (List<?>) resp.getBody().get("loaded_models");
            return loaded != null && !loaded.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fetches stats from {@code sourceSeason}, sends them to the Python API,
     * and stores the returned projections as predictions for {@code targetSeason}.
     *
     * @return summary string for the admin UI
     */
    @Transactional
    public String syncPredictions(int sourceSeason, int targetSeason) {
        log.info("Syncing predictions: {} stats -> {} projections", sourceSeason, targetSeason);

        // Wipe any existing predictions for this target season before re-syncing
        List<PlayerPrediction> existing = predictionRepository.findByPredictedSeason(targetSeason);
        if (!existing.isEmpty()) {
            predictionRepository.deleteAll(existing);
            log.info("Cleared {} existing predictions for season {}", existing.size(), targetSeason);
        }

        List<PlayerStat> stats = playerStatRepository.findBySeasonOrderByRankAsc(sourceSeason);
        if (stats.isEmpty()) {
            return "No stats found for season " + sourceSeason + ". Run a Sleeper sync first.";
        }

        // Build games-played map for sourceSeason: player_id → weeks played
        Map<Long, Integer> gamesPlayedMap = new HashMap<>();
        for (Object[] row : weeklyStatRepository.countWeeksByPlayerForSeason(sourceSeason)) {
            gamesPlayedMap.put((Long) row[0], ((Long) row[1]).intValue());
        }

        // Build weekly points map for sourceSeason: player_id → list of weekly scores
        Map<Long, List<Double>> weeklyPtsMap = new HashMap<>();
        for (Object[] row : weeklyStatRepository.findWeeklyPointsBySeason(sourceSeason)) {
            weeklyPtsMap.computeIfAbsent((Long) row[0], k -> new ArrayList<>())
                        .add(((Number) row[1]).doubleValue());
        }

        // Load prior-prior season stats (N-2) for trend features
        int prev2Season = sourceSeason - 1;
        Map<String, PlayerStat> prev2StatMap = new HashMap<>();
        for (PlayerStat ps : playerStatRepository.findBySeasonOrderByRankAsc(prev2Season)) {
            String key = ps.getPlayer().getFullName() + "|" + ps.getPlayer().getPosition();
            prev2StatMap.put(key, ps);
        }

        // Build games-played map for prev2Season
        Map<Long, Integer> prev2GamesMap = new HashMap<>();
        for (Object[] row : weeklyStatRepository.countWeeksByPlayerForSeason(prev2Season)) {
            prev2GamesMap.put((Long) row[0], ((Long) row[1]).intValue());
        }

        // Compute opponent-adjusted schedule strength for the source season
        Map<Long, Double> oppStrengthMap = computeOppStrength(sourceSeason);
        log.info("Computed opp_pts_allowed for {} players", oppStrengthMap.size());

        // Live depth-chart snapshot (today's roster, not sourceSeason's) — see
        // SleeperService.currentDepthChartOrders() javadoc for why this can't be a
        // trained feature and is only used as a prediction-time guardrail input.
        Map<String, Integer> depthChartMap;
        try {
            depthChartMap = sleeperService.currentDepthChartOrders();
            log.info("Fetched live depth chart for {} players", depthChartMap.size());
        } catch (Exception e) {
            log.warn("Could not fetch live depth chart, continuing without it: {}", e.getMessage());
            depthChartMap = Map.of();
        }
        final Map<String, Integer> depthChartOrders = depthChartMap;

        // Market signals — live ADP (free, no key) and consensus point projections (needs a
        // free FantasyPros key, see application.yml). Both are forward-looking in a way no
        // trained feature can be, since they price in offseason news. See serve.py's Guardrail
        // 5 for how these get blended into the final projection.
        Map<String, Double> adpMap;
        try {
            adpMap = fantasyCalculatorService.currentAdp(12);
        } catch (Exception e) {
            log.warn("Could not fetch ADP, continuing without it: {}", e.getMessage());
            adpMap = Map.of();
        }
        Map<String, Double> marketPointsMap;
        try {
            marketPointsMap = fantasyProsService.currentProjections(targetSeason);
        } catch (Exception e) {
            log.warn("Could not fetch FantasyPros projections, continuing without them: {}", e.getMessage());
            marketPointsMap = Map.of();
        }
        log.info("Fetched {} ADP entries, {} consensus projections", adpMap.size(), marketPointsMap.size());
        final Map<String, Double> adpByKey = adpMap;
        final Map<String, Double> marketPointsByKey = marketPointsMap;

        // Build request payload
        List<Map<String, Object>> players = stats.stream().map(s -> {
            int gp = gamesPlayedMap.getOrDefault(s.getPlayer().getId(), 0);
            String key = s.getPlayer().getFullName() + "|" + s.getPlayer().getPosition();
            PlayerStat prev2 = prev2StatMap.get(key);
            int prev2Gp = prev2 != null
                    ? prev2GamesMap.getOrDefault(prev2.getPlayer().getId(), 0) : 0;
            List<Double> wkPts = weeklyPtsMap.getOrDefault(s.getPlayer().getId(), List.of());
            ConsistencyStats cs = ConsistencyStats.of(wkPts);
            double oppPtsAllowed = oppStrengthMap.getOrDefault(s.getPlayer().getId(), 0.0);
            Integer depthChartOrder = depthChartOrders.get(s.getPlayer().getExternalId());
            // Separate normalized key -- market sources disagree on suffixes (e.g. FantasyPros'
            // "James Cook III" vs our "James Cook"), see NameUtil. prev2StatMap above is
            // internal-only (both sides are our own DB) so it doesn't need this.
            String marketKey = NameUtil.key(s.getPlayer().getFullName(), s.getPlayer().getPosition());
            Double marketAdp = adpByKey.get(marketKey);
            Double marketPoints = marketPointsByKey.get(marketKey);
            return statToPayload(s, gp, prev2, prev2Gp, cs, oppPtsAllowed, depthChartOrder,
                    marketAdp, marketPoints);
        }).collect(Collectors.toList());

        Map<String, Object> requestBody = Map.of("players", players);

        Map<?, ?> response;
        try {
            response = restTemplate.postForObject(
                    mlApiUrl + "/predict/season", requestBody, Map.class);
        } catch (Exception e) {
            log.error("ML API call failed: {}", e.getMessage());
            throw new RuntimeException("ML API unreachable: " + e.getMessage());
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> predictions = (List<Map<String, Object>>) response.get("predictions");

        // Build lookup: player name+position → stat (so we can resolve player entity)
        Map<String, PlayerStat> statLookup = stats.stream()
                .collect(Collectors.toMap(
                        s -> s.getPlayer().getFullName() + "|" + s.getPlayer().getPosition(),
                        s -> s,
                        (a, b) -> a));

        int saved = 0, errors = 0;
        for (Map<String, Object> pred : predictions) {
            String name     = (String)  pred.get("name");
            String position = (String)  pred.get("position");
            Object pts      = pred.get("projected_points");

            if (pts == null) { errors++; continue; }

            PlayerStat stat = statLookup.get(name + "|" + position);
            if (stat == null) { errors++; continue; }

            Player player = stat.getPlayer();
            PlayerPrediction entity = predictionRepository
                    .findByPlayer_IdAndPredictedSeason(player.getId(), targetSeason)
                    .orElse(new PlayerPrediction());

            entity.setPlayer(player);
            entity.setPredictedSeason(targetSeason);
            entity.setProjectedPoints(((Number) pts).doubleValue());
            predictionRepository.save(entity);
            saved++;
        }

        String msg = String.format(
                "Predictions synced — %d saved, %d skipped (no model or missing player).",
                saved, errors);
        log.info(msg);
        return msg;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Compute per-player schedule strength for the given season.
     *
     * Algorithm:
     *   1. For each (opponent_code, position) grouping, compute the average pts they allowed per game.
     *   2. For each player, average their opponents' defensive ratings across all weeks they played.
     *
     * Returns player_id → avg pts the opponents' defenses allowed to that position per game.
     * Higher = easier schedule (opponents let that position score more).
     * Returns empty map if no opponent data is present (column not yet backfilled).
     */
    private Map<Long, Double> computeOppStrength(int season) {
        List<Object[]> rows = weeklyStatRepository.findWeeklyPtsWithOpponentForSeason(season);
        if (rows.isEmpty()) return Map.of();

        // Step 1: defensive rating per (oppCode|position) → list of pts scored against them
        Map<String, List<Double>> defMap = new HashMap<>();
        // Track which (oppCode, position) each player faced each week
        Map<Long, List<String>> playerOppKeys = new HashMap<>();

        for (Object[] row : rows) {
            Long playerId  = (Long)   row[0];
            String oppCode = (String) row[1];
            double pts     = ((Number) row[2]).doubleValue();
            String position = (String) row[3];

            String defKey = oppCode + "|" + position;
            defMap.computeIfAbsent(defKey, k -> new ArrayList<>()).add(pts);
            playerOppKeys.computeIfAbsent(playerId, k -> new ArrayList<>()).add(defKey);
        }

        // Step 2: compute mean pts allowed per (def team, position)
        Map<String, Double> defRating = new HashMap<>();
        defMap.forEach((key, ptsList) ->
                defRating.put(key, ptsList.stream().mapToDouble(d -> d).average().orElse(0.0)));

        // Step 3: for each player, average their opponents' ratings
        Map<Long, Double> result = new HashMap<>();
        playerOppKeys.forEach((playerId, keys) -> {
            double sum = 0; int count = 0;
            for (String key : keys) {
                Double r = defRating.get(key);
                if (r != null) { sum += r; count++; }
            }
            if (count > 0)
                result.put(playerId, Math.round(sum / count * 100.0) / 100.0);
        });
        return result;
    }

    private Map<String, Object> statToPayload(PlayerStat s, int gamesPlayed,
                                              PlayerStat prev2, int prev2GamesPlayed,
                                              ConsistencyStats cs, double oppPtsAllowed,
                                              Integer depthChartOrder,
                                              Double marketAdp, Double marketPoints) {
        Player p = s.getPlayer();
        Map<String, Object> m = new HashMap<>();
        m.put("name",           p.getFullName());
        m.put("position",       p.getPosition());
        m.put("team",           p.getTeam() != null ? p.getTeam().getCode() : null);
        m.put("age",            p.getAge());
        // Live depth-chart slot (1 = current starter) as of right now, not sourceSeason —
        // see SleeperService.currentDepthChartOrders(). Never a trained feature.
        m.put("depth_chart_order", depthChartOrder);
        // Market signals (Guardrail 5 in serve.py) — never trained features, same reasoning
        // as depth_chart_order: both reflect today, not sourceSeason.
        m.put("market_adp",     marketAdp);
        m.put("market_points",  marketPoints);
        // Current roster status only — used as a prediction-time guardrail, never a
        // trainable historical feature (see Player.status javadoc for why).
        m.put("status",         p.getStatus());
        m.put("games_played",   gamesPlayed);
        m.put("total_points",   orZero(s.getTotalPoints()));
        m.put("passing_yds",    orZero(s.getPassingYds()));
        m.put("passing_td",     orZero(s.getPassingTd()));
        m.put("passing_int",    orZero(s.getPassingInt()));
        m.put("rushing_yds",    orZero(s.getRushingYds()));
        m.put("rushing_td",     orZero(s.getRushingTd()));
        m.put("targets",        orZero(s.getTargets()));
        m.put("receiving_rec",  orZero(s.getReceivingRec()));
        m.put("receiving_yds",  orZero(s.getReceivingYds()));
        m.put("receiving_td",   orZero(s.getReceivingTd()));
        m.put("fumbles",        orZero(s.getFumbles()));
        m.put("pat_made",       orZero(s.getPatMade()));
        m.put("pat_missed",     orZero(s.getPatMissed()));
        m.put("fg_made",        s.totalFgMade());
        m.put("def_sacks",      orZero(s.getDefSacks()));
        m.put("def_ints",       orZero(s.getDefInts()));
        m.put("def_fum_rec",    orZero(s.getDefFumRecoveries()));
        m.put("def_td",         orZero(s.getDefTd()));
        m.put("def_safeties",   orZero(s.getDefSafeties()));
        m.put("def_blocked_kicks", orZero(s.getDefBlockedKicks()));
        m.put("pts_allowed",    orZero(s.getPtsAllowed()));
        m.put("yds_allowed",    orZero(s.getYdsAllowed()));

        int tgt = orZero(s.getTargets());
        int rec = orZero(s.getReceivingRec());
        m.put("reception_pct", tgt > 0 ? Math.round(rec * 1000.0 / tgt) / 10.0 : 0.0);

        int offSnaps = orZero(s.getOffSnaps());
        int teamOffSnaps = orZero(s.getTeamOffSnaps());
        m.put("snap_pct", teamOffSnaps > 0 ? Math.round(offSnaps * 1000.0 / teamOffSnaps) / 10.0 : 0.0);
        m.put("off_snaps", offSnaps);

        // Two-season trend features
        double prev2Pts  = prev2 != null ? orZero(prev2.getTotalPoints()) : 0.0;
        double prev2Ppg  = prev2GamesPlayed > 0 ? prev2Pts / prev2GamesPlayed : 0.0;
        m.put("prev2_total_points",    prev2Pts);
        m.put("prev2_games_played",    prev2GamesPlayed);
        m.put("prev2_points_per_game", Math.round(prev2Ppg * 100.0) / 100.0);
        m.put("pts_delta",             prev2 != null ? orZero(s.getTotalPoints()) - prev2Pts : 0.0);
        m.put("has_prev2",             prev2 != null ? 1 : 0);

        // Weekly consistency features
        m.put("weekly_pts_std", Math.round(cs.std     * 100.0) / 100.0);
        m.put("floor",          Math.round(cs.floor   * 100.0) / 100.0);
        m.put("ceiling",        Math.round(cs.ceiling * 100.0) / 100.0);
        m.put("games_over_10",  cs.gamesOver10);
        m.put("games_over_20",  cs.gamesOver20);

        // Opponent-adjusted schedule strength: avg pts the player's opponents allowed to their position
        // Higher = played against weaker defenses; 0 = opponent data not yet backfilled
        m.put("opp_pts_allowed", Math.round(oppPtsAllowed * 100.0) / 100.0);

        return m;
    }

    private static int orZero(Integer v)  { return v != null ? v : 0; }
    private static double orZero(Double v) { return v != null ? v : 0.0; }
}
