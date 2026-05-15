package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.domain.Player;
import com.firstember.fantasyfootball.domain.PlayerStat;
import com.firstember.fantasyfootball.repo.PlayerRepository;
import com.firstember.fantasyfootball.repo.PlayerStatRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.Comparator;
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
    public String list(@RequestParam(required = false) Integer year, Model model) {
        List<Integer> availableYears = playerRepository.findDistinctSeasons();
        int selectedYear = resolveYear(year, availableYears);

        List<Player> players = new ArrayList<>(playerRepository.findBySeason(selectedYear));

        List<Long> ids = players.stream().map(Player::getId).collect(Collectors.toList());
        Map<Long, PlayerStat> statsMap = playerStatRepository.findByPlayer_IdIn(ids)
                .stream()
                .collect(Collectors.toMap(s -> s.getPlayer().getId(), s -> s));

        // Sort by fantasy points descending
        players.sort(Comparator.comparingDouble((Player p) -> {
            PlayerStat s = statsMap.get(p.getId());
            return (s != null && s.getTotalPoints() != null) ? s.getTotalPoints() : -1.0;
        }).reversed());

        model.addAttribute("players", players);
        model.addAttribute("statsMap", statsMap);
        model.addAttribute("playerCount", players.size());
        model.addAttribute("availableYears", availableYears);
        model.addAttribute("selectedYear", selectedYear);
        return "players/index";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Player player = playerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Player not found: " + id));

        Integer season = player.getSeason();
        PlayerStat stat = season != null
                ? playerStatRepository.findByPlayer_IdAndSeason(id, season).orElse(null)
                : null;

        // How many players at the same position scored more this season?
        int positionRank = 1;
        if (stat != null && stat.getTotalPoints() != null) {
            List<PlayerStat> posStats = playerStatRepository
                    .findByPlayer_PositionAndSeasonOrderByRankAsc(player.getPosition(), season);
            positionRank = (int) posStats.stream()
                    .filter(s -> s.getTotalPoints() != null && s.getTotalPoints() > stat.getTotalPoints())
                    .count() + 1;
        }

        model.addAttribute("player", player);
        model.addAttribute("stat", stat);
        model.addAttribute("positionRank", positionRank);
        return "players/detail";
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static int resolveYear(Integer requested, List<Integer> available) {
        if (requested != null) return requested;
        return available.isEmpty() ? 2024 : available.get(0); // list is sorted DESC
    }
}
