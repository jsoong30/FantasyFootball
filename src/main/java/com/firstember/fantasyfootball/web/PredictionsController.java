package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.domain.Player;
import com.firstember.fantasyfootball.domain.PlayerPrediction;
import com.firstember.fantasyfootball.domain.PlayerStat;
import com.firstember.fantasyfootball.ml.MlPredictionService;
import com.firstember.fantasyfootball.repo.PlayerPredictionRepository;
import com.firstember.fantasyfootball.repo.PlayerRepository;
import com.firstember.fantasyfootball.repo.PlayerStatRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/predictions")
public class PredictionsController {

    private static final int TARGET_SEASON = 2026;
    private static final int SOURCE_SEASON = 2025;

    private final PlayerRepository playerRepository;
    private final PlayerStatRepository playerStatRepository;
    private final PlayerPredictionRepository predictionRepository;
    private final MlPredictionService mlPredictionService;

    public PredictionsController(PlayerRepository playerRepository,
                                 PlayerStatRepository playerStatRepository,
                                 PlayerPredictionRepository predictionRepository,
                                 MlPredictionService mlPredictionService) {
        this.playerRepository = playerRepository;
        this.playerStatRepository = playerStatRepository;
        this.predictionRepository = predictionRepository;
        this.mlPredictionService = mlPredictionService;
    }

    @GetMapping
    public String index(@RequestParam(required = false) String position, Model model) {
        // Load 2025 players and their actual stats
        List<Player> players = new ArrayList<>(playerRepository.findBySeason(SOURCE_SEASON));
        List<Long> ids = players.stream().map(Player::getId).collect(Collectors.toList());

        Map<Long, PlayerStat> statsMap = playerStatRepository.findByPlayer_IdIn(ids)
                .stream()
                .collect(Collectors.toMap(s -> s.getPlayer().getId(), s -> s));

        // Load stored predictions for 2026
        Map<Long, PlayerPrediction> predictionsMap = predictionRepository
                .findByPredictedSeason(TARGET_SEASON)
                .stream()
                .collect(Collectors.toMap(p -> p.getPlayer().getId(), p -> p));

        boolean modelConnected = !predictionsMap.isEmpty();

        // Sort: if predictions exist sort by projected points, otherwise by actual points
        players.sort(Comparator.comparingDouble((Player p) -> {
            if (modelConnected) {
                PlayerPrediction pred = predictionsMap.get(p.getId());
                return (pred != null && pred.getProjectedPoints() != null)
                        ? pred.getProjectedPoints() : -1.0;
            }
            PlayerStat s = statsMap.get(p.getId());
            return (s != null && s.getTotalPoints() != null) ? s.getTotalPoints() : -1.0;
        }).reversed());

        // Compute pct change: player_id → delta % (projected vs actual)
        // Positive = trending up, negative = trending down
        Map<Long, Double> deltaMap = new HashMap<>();
        if (modelConnected) {
            for (Player p : players) {
                PlayerStat stat = statsMap.get(p.getId());
                PlayerPrediction pred = predictionsMap.get(p.getId());
                if (stat != null && stat.getTotalPoints() != null && stat.getTotalPoints() > 0
                        && pred != null && pred.getProjectedPoints() != null) {
                    double pct = (pred.getProjectedPoints() - stat.getTotalPoints())
                                 / stat.getTotalPoints() * 100.0;
                    deltaMap.put(p.getId(), Math.round(pct * 10.0) / 10.0);
                }
            }
        }

        model.addAttribute("players", players);
        model.addAttribute("statsMap", statsMap);
        model.addAttribute("predictionsMap", predictionsMap);
        model.addAttribute("deltaMap", deltaMap);
        model.addAttribute("modelConnected", modelConnected);
        model.addAttribute("sourceSeason", SOURCE_SEASON);
        model.addAttribute("targetSeason", TARGET_SEASON);
        return "predictions/index";
    }
}
