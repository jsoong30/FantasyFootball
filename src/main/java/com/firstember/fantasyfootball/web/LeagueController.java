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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every league here is private to whoever added it -- see {@link FantasyLeague}'s javadoc for
 * why. Every lookup below is scoped by {@code principal.getUser().getId()}, not just by league
 * id, so one user can never view/sync/delete another user's league even by guessing its id.
 */
@Controller
@RequestMapping("/league")
public class LeagueController {

    /** Football-conventional roster order, so a team's players read QB → DST rather than alphabetically. */
    private static final List<String> POSITION_ORDER = List.of("QB", "RB", "WR", "TE", "K", "DST");

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
        List<FantasyLeague> leagues = leagueRepository.findByOwner_Id(principal.getUser().getId());
        model.addAttribute("leagues", leagues);
        model.addAttribute("syncedAgoMap", syncedAgoMap(leagues));
        model.addAttribute("addMessage", null);
        return "league/index";
    }

    /** Add (and immediately sync) a league by its Sleeper league id, owned by the current user. */
    @PostMapping("/add")
    public String add(@RequestParam String sleeperLeagueId,
                      @AuthenticationPrincipal AppUserPrincipal principal,
                      Model model) {
        String result = fantasyLeagueService.syncLeague(sleeperLeagueId.trim(), principal.getUser());
        List<FantasyLeague> leagues = leagueRepository.findByOwner_Id(principal.getUser().getId());
        model.addAttribute("leagues", leagues);
        model.addAttribute("syncedAgoMap", syncedAgoMap(leagues));
        model.addAttribute("addMessage", result);
        return "league/index";
    }

    private Map<Long, String> syncedAgoMap(List<FantasyLeague> leagues) {
        Map<Long, String> map = new LinkedHashMap<>();
        for (FantasyLeague lg : leagues) map.put(lg.getId(), relativeTime(lg.getRostersLastSyncedAt()));
        return map;
    }

    /**
     * Re-sync one already-added league — only if it belongs to the current user. Captures and
     * flashes {@code syncLeague}'s result (success or error text) so a failure is never silent —
     * this used to fire-and-forget the call with no feedback, which made a real failure (a bad
     * league id, Sleeper being briefly unreachable) look identical to a successful no-op.
     */
    @PostMapping("/{id}/sync")
    public String sync(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
                       RedirectAttributes redirectAttributes) {
        String result = leagueRepository.findByIdAndOwner_Id(id, principal.getUser().getId())
                .map(league -> fantasyLeagueService.syncLeague(league.getSleeperLeagueId(), principal.getUser()))
                .orElse("League not found.");
        redirectAttributes.addFlashAttribute("syncMessage", result);
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
                .thenComparing(FantasyTeam::getLosses, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(FantasyTeam::getPointsFor, Comparator.nullsLast(Comparator.reverseOrder())));

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
                    .sorted(Comparator.comparingInt((Player p) -> {
                                int i = POSITION_ORDER.indexOf(p.getPosition());
                                return i < 0 ? POSITION_ORDER.size() : i;
                            })
                            .thenComparing(Player::getFullName))
                    .toList();
            rosterDisplay.put(team.getId(), players);
        }

        // Group teams by division for the detail view (0 = no division). TreeMap -> ascending order.
        boolean hasDivisions = teams.stream().anyMatch(t -> t.getDivision() != null);
        Map<Integer, List<FantasyTeam>> teamsByDivision = new TreeMap<>();
        for (FantasyTeam t : teams) {
            teamsByDivision.computeIfAbsent(t.getDivision() != null ? t.getDivision() : 0,
                    k -> new ArrayList<>()).add(t);
        }

        // The roster panel opens on the viewer's own team, falling back to the top of the standings.
        Long selectedTeamId = teams.stream()
                .filter(t -> Boolean.TRUE.equals(mineMap.get(t.getId())))
                .map(FantasyTeam::getId)
                .findFirst()
                .orElse(teams.isEmpty() ? null : teams.get(0).getId());

        model.addAttribute("league", league);
        model.addAttribute("teams", teams);
        model.addAttribute("rosterDisplay", rosterDisplay);
        model.addAttribute("mineMap", mineMap);
        model.addAttribute("teamsByDivision", teamsByDivision);
        model.addAttribute("hasDivisions", hasDivisions);
        model.addAttribute("selectedTeamId", selectedTeamId);
        model.addAttribute("syncedAgo", relativeTime(league.getRostersLastSyncedAt()));
        return "league/detail";
    }

    /**
     * Human-readable "synced X ago" for {@link FantasyLeague#getRostersLastSyncedAt()}. Computed
     * here rather than in the template — Thymeleaf's temporal utilities don't handle
     * {@code java.time.Instant} cleanly, and this is easier to get right in Java.
     */
    private static String relativeTime(Instant instant) {
        if (instant == null) return "never synced";
        long mins = Duration.between(instant, Instant.now()).toMinutes();
        if (mins < 1) return "just now";
        if (mins < 60) return "synced " + mins + " min ago";
        long hours = mins / 60;
        if (hours < 24) return "synced " + hours + " hr" + (hours == 1 ? "" : "s") + " ago";
        long days = hours / 24;
        return "synced " + days + " day" + (days == 1 ? "" : "s") + " ago";
    }
}
