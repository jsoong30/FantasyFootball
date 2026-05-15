package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.domain.PlayerStat;
import com.firstember.fantasyfootball.repo.PlayerRepository;
import com.firstember.fantasyfootball.repo.PlayerStatRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequestMapping("/rankings")
public class RankingsController {

    private final PlayerRepository playerRepository;
    private final PlayerStatRepository playerStatRepository;

    public RankingsController(PlayerRepository playerRepository,
                              PlayerStatRepository playerStatRepository) {
        this.playerRepository = playerRepository;
        this.playerStatRepository = playerStatRepository;
    }

    @GetMapping
    public String rankings(@RequestParam(defaultValue = "ALL") String position,
                           @RequestParam(required = false) Integer year,
                           Model model) {

        List<Integer> availableYears = playerRepository.findDistinctSeasons();
        int selectedYear = resolveYear(year, availableYears);

        List<PlayerStat> stats;
        if ("ALL".equals(position)) {
            stats = playerStatRepository.findBySeasonOrderByRankAsc(selectedYear);
        } else {
            stats = playerStatRepository.findByPlayer_PositionAndSeasonOrderByRankAsc(position, selectedYear);
        }

        model.addAttribute("stats", stats);
        model.addAttribute("selectedPosition", position);
        model.addAttribute("availableYears", availableYears);
        model.addAttribute("selectedYear", selectedYear);
        return "rankings/index";
    }

    private static int resolveYear(Integer requested, List<Integer> available) {
        if (requested != null) return requested;
        return available.isEmpty() ? 2024 : available.get(0);
    }
}
