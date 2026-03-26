package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.domain.Player;
import com.firstember.fantasyfootball.domain.PlayerStat;
import com.firstember.fantasyfootball.repo.PlayerRepository;
import com.firstember.fantasyfootball.repo.PlayerStatRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/predictions")
public class PredictionsController {

    private final PlayerRepository playerRepository;
    private final PlayerStatRepository playerStatRepository;

    public PredictionsController(PlayerRepository playerRepository,
                                 PlayerStatRepository playerStatRepository) {
        this.playerRepository = playerRepository;
        this.playerStatRepository = playerStatRepository;
    }

    @GetMapping
    public String index(Model model) {
        List<Player> players = new ArrayList<>(playerRepository.findAllByOrderByFullNameAsc());

        List<Long> ids = players.stream().map(Player::getId).collect(Collectors.toList());
        Map<Long, PlayerStat> statsMap = playerStatRepository.findByPlayer_IdIn(ids)
                .stream()
                .collect(Collectors.toMap(s -> s.getPlayer().getId(), s -> s));

        players.sort(Comparator.comparingDouble((Player p) -> {
            PlayerStat s = statsMap.get(p.getId());
            return (s != null && s.getTotalPoints() != null) ? s.getTotalPoints() : -1.0;
        }).reversed());

        // modelConnected drives the banner — flip to true once the Python API is live
        model.addAttribute("players", players);
        model.addAttribute("statsMap", statsMap);
        model.addAttribute("modelConnected", false);
        return "predictions/index";
    }
}
