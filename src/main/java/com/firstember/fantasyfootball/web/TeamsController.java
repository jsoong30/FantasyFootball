package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.domain.Player;
import com.firstember.fantasyfootball.domain.PlayerStat;
import com.firstember.fantasyfootball.repo.PlayerRepository;
import com.firstember.fantasyfootball.repo.PlayerStatRepository;
import com.firstember.fantasyfootball.repo.TeamRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/teams")
public class TeamsController {

    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final PlayerStatRepository playerStatRepository;

    public TeamsController(TeamRepository teamRepository,
                           PlayerRepository playerRepository,
                           PlayerStatRepository playerStatRepository) {
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.playerStatRepository = playerStatRepository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("teams", teamRepository.findAllByOrderByCodeAsc());
        model.addAttribute("teamCount", teamRepository.count());
        return "teams/index";
    }

    @GetMapping("/{code}")
    public String detail(@PathVariable String code, Model model) {
        var team = teamRepository.findAll().stream()
                .filter(t -> t.getCode().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown team: " + code));

        List<Player> players = new ArrayList<>(playerRepository.findByTeam_CodeOrderByFullNameAsc(code.toUpperCase()));

        List<Long> ids = players.stream().map(Player::getId).collect(Collectors.toList());
        Map<Long, PlayerStat> statsMap = playerStatRepository.findByPlayer_IdIn(ids).stream()
                .collect(Collectors.toMap(s -> s.getPlayer().getId(), s -> s));

        players.sort(Comparator.comparingDouble((Player p) -> {
            PlayerStat s = statsMap.get(p.getId());
            return (s != null && s.getTotalPoints() != null) ? s.getTotalPoints() : -1.0;
        }).reversed());

        model.addAttribute("team", team);
        model.addAttribute("players", players);
        model.addAttribute("statsMap", statsMap);
        return "teams/detail";
    }
}
