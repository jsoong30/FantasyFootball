package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.config.SeasonConfig;
import com.firstember.fantasyfootball.repo.PlayerPredictionRepository;
import com.firstember.fantasyfootball.repo.PlayerRepository;
import com.firstember.fantasyfootball.repo.TeamRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    private final SeasonConfig seasonConfig;
    private final PlayerRepository playerRepository;
    private final TeamRepository teamRepository;
    private final PlayerPredictionRepository predictionRepository;

    public HomeController(SeasonConfig seasonConfig,
                          PlayerRepository playerRepository,
                          TeamRepository teamRepository,
                          PlayerPredictionRepository predictionRepository) {
        this.seasonConfig = seasonConfig;
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
        model.addAttribute("modelConnected",
                predictionRepository.existsByPredictedSeason(seasonConfig.getTargetSeason()));
        return "index";
    }
}
