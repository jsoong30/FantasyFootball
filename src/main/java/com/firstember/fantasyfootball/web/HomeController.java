package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.repo.PlayerPredictionRepository;
import com.firstember.fantasyfootball.repo.PlayerRepository;
import com.firstember.fantasyfootball.repo.TeamRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    // Kept in sync with PredictionsController.TARGET_SEASON
    private static final int TARGET_SEASON = 2026;

    private final PlayerRepository playerRepository;
    private final TeamRepository teamRepository;
    private final PlayerPredictionRepository predictionRepository;

    public HomeController(PlayerRepository playerRepository,
                          TeamRepository teamRepository,
                          PlayerPredictionRepository predictionRepository) {
        this.playerRepository = playerRepository;
        this.teamRepository = teamRepository;
        this.predictionRepository = predictionRepository;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("appName", "FantasyFootball");
        model.addAttribute("welcomeMsg", "Welcome to your Fantasy Football Tracker!");
        model.addAttribute("playerCount", playerRepository.count());
        model.addAttribute("teamCount", teamRepository.countByCodeNot("FA"));
        model.addAttribute("modelConnected", predictionRepository.existsByPredictedSeason(TARGET_SEASON));
        return "index";
    }
}
