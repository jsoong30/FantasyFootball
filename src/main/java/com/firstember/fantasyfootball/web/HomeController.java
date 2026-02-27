package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.repo.PlayerRepository;
import com.firstember.fantasyfootball.repo.TeamRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    private final PlayerRepository playerRepository;
    private final TeamRepository teamRepository;

    public HomeController(PlayerRepository playerRepository, TeamRepository teamRepository) {
        this.playerRepository = playerRepository;
        this.teamRepository = teamRepository;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("appName", "FantasyFootball");
        model.addAttribute("welcomeMsg", "Welcome to your Fantasy Football Tracker!");
        model.addAttribute("playerCount", playerRepository.count());
        model.addAttribute("teamCount", teamRepository.count());
        return "index";
    }
}
