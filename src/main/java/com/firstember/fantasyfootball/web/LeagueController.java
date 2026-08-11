package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.config.AppUserPrincipal;
import com.firstember.fantasyfootball.domain.FantasyLeague;
import com.firstember.fantasyfootball.domain.FantasyRosterPlayer;
import com.firstember.fantasyfootball.domain.FantasyTeam;
import com.firstember.fantasyfootball.domain.Player;
import com.firstember.fantasyfootball.repo.FantasyLeagueRepository;
import com.firstember.fantasyfootball.repo.FantasyTeamRepository;
import com.firstember.fantasyfootball.repo.PlayerRepository;
import com.firstember.fantasyfootball.sleeper.FantasyLeagueService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every league here is private to whoever added it -- see {@link FantasyLeague}'s javadoc for
 * why. Every lookup below is scoped by {@code principal.getUser().getId()}, not just by league
 * id, so one user can never view/sync/delete another user's league even by guessing its id.
 */
@Controller
@RequestMapping("/league")
public class LeagueController {

    private final FantasyLeagueRepository leagueRepository;
    private final FantasyTeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final FantasyLeagueService fantasyLeagueService;

    public LeagueController(FantasyLeagueRepository leagueRepository,
                            FantasyTeamRepository teamRepository,
                            PlayerRepository playerRepository,
                            FantasyLeagueService fantasyLeagueService) {
        this.leagueRepository = leagueRepository;
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.fantasyLeagueService = fantasyLeagueService;
    }

    /** List only leagues the current user added — pick one to view its teams, or add a new one. */
    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("leagues", leagueRepository.findByOwner_Id(principal.getUser().getId()));
        model.addAttribute("addMessage", null);
        return "league/index";
    }

    /** Add (and immediately sync) a league by its Sleeper league id, owned by the current user. */
    @PostMapping("/add")
    public String add(@RequestParam String sleeperLeagueId,
                      @AuthenticationPrincipal AppUserPrincipal principal,
                      Model model) {
        String result = fantasyLeagueService.syncLeague(sleeperLeagueId.trim(), principal.getUser());
        model.addAttribute("leagues", leagueRepository.findByOwner_Id(principal.getUser().getId()));
        model.addAttribute("addMessage", result);
        return "league/index";
    }

    /** Re-sync one already-added league — only if it belongs to the current user. */
    @PostMapping("/{id}/sync")
    public String sync(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal) {
        leagueRepository.findByIdAndOwner_Id(id, principal.getUser().getId())
                .ifPresent(league -> fantasyLeagueService.syncLeague(league.getSleeperLeagueId(), principal.getUser()));
        return "redirect:/league/" + id;
    }

    /** Remove a league and everything under it — only if it belongs to the current user. */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal) {
        fantasyLeagueService.deleteLeague(id, principal.getUser().getId());
        return "redirect:/league";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         @AuthenticationPrincipal AppUserPrincipal principal,
                         Model model) {
        FantasyLeague league = leagueRepository.findByIdAndOwner_Id(id, principal.getUser().getId()).orElse(null);
        if (league == null) {
            model.addAttribute("league", null);
            return "league/detail";
        }

        List<FantasyTeam> teams = teamRepository.findByLeague_IdOrderBySleeperRosterIdAsc(league.getId());
        teams.sort(Comparator
                .comparing(FantasyTeam::getWins, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(FantasyTeam::getLosses, Comparator.nullsLast(Comparator.naturalOrder())));

        // Within your own private league, "is this MY team" still needs resolving per-viewer --
        // there are still 11 other teams belonging to leaguemates who aren't you.
        String myUserId = principal.getUser().getSleeperUserId();
        Map<Long, Boolean> mineMap = new LinkedHashMap<>();

        // Resolve each roster's Sleeper player ids to display-ready Player rows (latest synced season).
        Map<Long, List<Player>> rosterDisplay = new LinkedHashMap<>();
        for (FantasyTeam team : teams) {
            mineMap.put(team.getId(), myUserId != null && myUserId.equals(team.getOwnerUserId()));
            List<Player> players = team.getRosterPlayers().stream()
                    .map(FantasyRosterPlayer::getSleeperPlayerId)
                    .map(playerRepository::findFirstByExternalIdOrderBySeasonDesc)
                    .flatMap(java.util.Optional::stream)
                    .sorted(Comparator.comparing(Player::getPosition))
                    .toList();
            rosterDisplay.put(team.getId(), players);
        }

        model.addAttribute("league", league);
        model.addAttribute("teams", teams);
        model.addAttribute("rosterDisplay", rosterDisplay);
        model.addAttribute("mineMap", mineMap);
        return "league/detail";
    }
}
