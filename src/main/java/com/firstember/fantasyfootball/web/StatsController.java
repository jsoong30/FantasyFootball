package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.domain.PlayerWeeklyStat;
import com.firstember.fantasyfootball.repo.PlayerWeeklyStatRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Year;
import java.util.List;

@Controller
@RequestMapping("/stats")
public class StatsController {

    private final PlayerWeeklyStatRepository weeklyStatRepository;

    public StatsController(PlayerWeeklyStatRepository weeklyStatRepository) {
        this.weeklyStatRepository = weeklyStatRepository;
    }

    @GetMapping
    public String index(@RequestParam(required = false) Integer year,
                        @RequestParam(required = false) Integer week,
                        Model model) {
        List<Integer> availableYears = weeklyStatRepository.findDistinctSeasons();
        int selectedYear = year != null ? year
                : availableYears.isEmpty() ? Year.now().getValue() : availableYears.get(0);

        List<Integer> availableWeeks = weeklyStatRepository.findDistinctWeeksBySeason(selectedYear);
        Integer selectedWeek = week != null ? week
                : availableWeeks.isEmpty() ? null : availableWeeks.get(0);

        List<PlayerWeeklyStat> weeklyStats = selectedWeek != null
                ? weeklyStatRepository.findBySeasonAndWeekOrderByTotalPointsDesc(selectedYear, selectedWeek)
                : List.of();

        model.addAttribute("availableYears", availableYears);
        model.addAttribute("availableWeeks", availableWeeks);
        model.addAttribute("selectedYear", selectedYear);
        model.addAttribute("selectedWeek", selectedWeek);
        model.addAttribute("weeklyStats", weeklyStats);
        return "stats/index";
    }
}
