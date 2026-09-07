package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.domain.Player;
import com.firstember.fantasyfootball.repo.PlayerRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequestMapping("/players")
public class PlayersController {

    private final PlayerRepository playerRepository;

    public PlayersController(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String position, Model model) {
        List<Player> players;

        if (position != null && !position.isBlank()) {
            players = playerRepository.findByPositionOrderByFullNameAsc(position.toUpperCase());
            model.addAttribute("selectedPosition", position.toUpperCase());
        } else {
            players = playerRepository.findAllByOrderByFullNameAsc();
            model.addAttribute("selectedPosition", "");
        }

        model.addAttribute("players", players);
        model.addAttribute("playerCount", playerRepository.count());
        return "players/index";
    }
}
