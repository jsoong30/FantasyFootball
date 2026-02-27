package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.domain.Player;
import com.firstember.fantasyfootball.domain.PlayerStat;
import com.firstember.fantasyfootball.repo.PlayerRepository;
import com.firstember.fantasyfootball.repo.PlayerStatRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/players")
public class PlayersController {

    private final PlayerRepository playerRepository;
    private final PlayerStatRepository playerStatRepository;

    public PlayersController(PlayerRepository playerRepository,
                             PlayerStatRepository playerStatRepository) {
        this.playerRepository = playerRepository;
        this.playerStatRepository = playerStatRepository;
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

        // Fetch stats for displayed players and build playerId -> PlayerStat map
        List<Long> ids = players.stream().map(Player::getId).collect(Collectors.toList());
        Map<Long, PlayerStat> statsMap = playerStatRepository.findByPlayer_IdIn(ids)
                .stream()
                .collect(Collectors.toMap(s -> s.getPlayer().getId(), s -> s));

        model.addAttribute("players", players);
        model.addAttribute("statsMap", statsMap);
        model.addAttribute("playerCount", playerRepository.count());
        return "players/index";
    }
}
