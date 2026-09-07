package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.repo.TeamRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/teams")
public class TeamsController {

    private final TeamRepository teamRepository;

    public TeamsController(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("teams", teamRepository.findAllByOrderByCodeAsc());
        model.addAttribute("teamCount", teamRepository.count());
        return "teams/index";
    }
}
