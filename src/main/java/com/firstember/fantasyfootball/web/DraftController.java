package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.config.AppUserPrincipal;
import com.firstember.fantasyfootball.domain.FantasyLeague;
import com.firstember.fantasyfootball.domain.FantasyTeam;
import com.firstember.fantasyfootball.domain.Player;
import com.firstember.fantasyfootball.domain.PlayerPrediction;
import com.firstember.fantasyfootball.external.FantasyCalculatorService;
import com.firstember.fantasyfootball.external.NameUtil;
import com.firstember.fantasyfootball.repo.FantasyLeagueRepository;
import com.firstember.fantasyfootball.repo.FantasyTeamRepository;
import com.firstember.fantasyfootball.repo.PlayerPredictionRepository;
import com.firstember.fantasyfootball.sleeper.DraftService;
import com.firstember.fantasyfootball.sleeper.SleeperDraftDTO;
import com.firstember.fantasyfootball.sleeper.SleeperDraftPickDTO;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.*;

/**
 * Live draft board — polled every few seconds by draft/board.html while a draft is active
 * (Sleeper has no push/websocket feed for third-party apps). Works against ANY Sleeper draft id,
 * including a standalone practice "mock draft" with no real league — see DraftService javadoc
 * for why that's the intended way to test this before a real draft.
 */
@Controller
@RequestMapping("/draft")
public class DraftController {

    // Same target season PredictionsController projects for — a draft happening now is for
    // the upcoming season, which is exactly what our stored predictions already represent.
    private static final int TARGET_SEASON = 2026;
    private static final int SUGGESTION_LIMIT = 150;

    private final DraftService draftService;
    private final FantasyLeagueRepository leagueRepository;
    private final FantasyTeamRepository teamRepository;
    private final PlayerPredictionRepository predictionRepository;
    private final FantasyCalculatorService fantasyCalculatorService;

    public DraftController(DraftService draftService,
                           FantasyLeagueRepository leagueRepository,
                           FantasyTeamRepository teamRepository,
                           PlayerPredictionRepository predictionRepository,
                           FantasyCalculatorService fantasyCalculatorService) {
        this.draftService = draftService;
        this.leagueRepository = leagueRepository;
        this.teamRepository = teamRepository;
        this.predictionRepository = predictionRepository;
        this.fantasyCalculatorService = fantasyCalculatorService;
    }

    @GetMapping("/{draftId}")
    public String board(@PathVariable String draftId, Model model) {
        model.addAttribute("draftId", draftId);
        return "draft/board";
    }

    /** Polling target — the frontend hits this on an interval and re-renders from the JSON. */
    @GetMapping("/{draftId}/data")
    @ResponseBody
    public Map<String, Object> data(@PathVariable String draftId,
                                    @AuthenticationPrincipal AppUserPrincipal principal) {
        SleeperDraftDTO draft = draftService.getDraft(draftId);
        List<SleeperDraftPickDTO> picks = draftService.getPicks(draftId);

        // If this draft belongs to one of the user's own synced leagues, persist new picks into
        // its real rosters and use real team/owner labels. Otherwise (e.g. a standalone mock
        // draft) there's nothing to persist into.
        FantasyLeague league = principal != null
                ? leagueRepository.findBySleeperDraftIdAndOwner_Id(draftId, principal.getUser().getId()).orElse(null)
                : null;
        if (league != null) draftService.syncPicksToRoster(league, picks);

        String myUserId = principal != null ? principal.getUser().getSleeperUserId() : null;

        // roster_id -> real team label/owner, for a linked league. roster_id is a stable team
        // identity for the whole draft regardless of draft position, unlike draft_slot -- see
        // below for why draft_slot can't be trusted as a fixed team identity.
        Map<Integer, String> rosterLabel = new HashMap<>();
        Map<Integer, String> rosterOwnerUserId = new HashMap<>();
        if (league != null) {
            for (FantasyTeam t : teamRepository.findByLeague_IdOrderBySleeperRosterIdAsc(league.getId())) {
                rosterLabel.put(t.getSleeperRosterId(), t.getDisplayLabel());
                rosterOwnerUserId.put(t.getSleeperRosterId(), t.getOwnerUserId());
            }
        }

        int numSlots = draft != null && draft.getTeams() != null ? draft.getTeams() : rosterLabel.size();
        int totalRounds = draft != null && draft.getRounds() != null ? draft.getRounds() : 15;

        // slotGuess: a best-effort "who's currently sitting in this column" label for the header
        // row. Seeded from Sleeper's pre-draft slot_to_roster_id / draft_order, then overwritten
        // as real picks come in. This is ONLY for the header -- every individual pick below
        // always shows its own actual team, resolved fresh from that pick's own roster_id/
        // picked_by, never from this guess. That distinction is exactly why this matters: a
        // league that snakes for 2 rounds then re-scrambles the order means draft_slot 3 might
        // be Team A in round 1 and Team F in round 3 -- a single fixed header per column would
        // be wrong for half the board. Sleeper's own UI has this same problem and solves it the
        // same way (a small per-pick label), which is what this mirrors.
        Map<Integer, String> slotGuess = new HashMap<>();
        if (league != null && draft != null && draft.getSlotToRosterId() != null) {
            draft.getSlotToRosterId().forEach((slotStr, rosterId) -> {
                String label = rosterLabel.get(rosterId);
                if (label != null) slotGuess.put(Integer.parseInt(slotStr), label);
            });
        }
        Integer mySlot = null;
        if (league == null && draft != null && draft.getDraftOrder() != null && myUserId != null) {
            mySlot = draft.getDraftOrder().get(myUserId);
        }
        for (int slot = 1; slot <= numSlots; slot++) {
            slotGuess.putIfAbsent(slot, (mySlot != null && slot == mySlot) ? "You" : "Team " + slot);
        }

        Set<String> draftedPlayerIds = new HashSet<>();
        List<Map<String, Object>> allPicks = new ArrayList<>();

        for (SleeperDraftPickDTO p : picks) {
            if (p.getPlayerId() == null) continue;
            draftedPlayerIds.add(p.getPlayerId());

            // The pick's own data is always the source of truth for who made it -- roster_id for
            // a linked league (stable team identity), picked_by (Sleeper user id) otherwise.
            // Never trust draft position/slot for team identity; only for column placement.
            boolean mine;
            String teamLabel;
            if (league != null && p.getRosterId() != null) {
                teamLabel = rosterLabel.getOrDefault(p.getRosterId(), "Team ?");
                String ownerUserId = rosterOwnerUserId.get(p.getRosterId());
                mine = myUserId != null && myUserId.equals(ownerUserId);
            } else {
                mine = myUserId != null && myUserId.equals(p.getPickedBy());
                teamLabel = mine ? "You" : "Team " + p.getDraftSlot();
            }
            if (p.getDraftSlot() != null) slotGuess.put(p.getDraftSlot(), teamLabel);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("round", p.getRound());
            row.put("slot", p.getDraftSlot());
            row.put("pickNo", p.getPickNo());
            row.put("name", p.getDisplayName());
            row.put("position", p.getPosition());
            row.put("team", teamLabel);
            row.put("isMine", mine);
            allPicks.add(row);
        }

        List<Map<String, Object>> recentPicks = new ArrayList<>(allPicks);
        recentPicks.sort((a, b) -> ((Integer) b.get("pickNo")).compareTo((Integer) a.get("pickNo")));
        List<Map<String, Object>> lastTen = recentPicks.subList(0, Math.min(10, recentPicks.size()));

        List<String> slotHeaders = new ArrayList<>();
        for (int slot = 1; slot <= numSlots; slot++) slotHeaders.add(slotGuess.get(slot));

        // "On the clock" -- the SLOT due next is always deterministic (standard snake traversal
        // of column positions 1..N, alternating direction each round), even in a re-scrambled
        // league, since it's only the *team occupying* a slot that changes, not the traversal
        // pattern itself. The team label shown for it is the same best-effort header guess.
        Map<String, Object> onClock = null;
        if (numSlots > 0 && draftedPlayerIds.size() < (long) numSlots * totalRounds) {
            int pickNumber = draftedPlayerIds.size() + 1;
            int round = (pickNumber - 1) / numSlots + 1;
            int posInRound = (pickNumber - 1) % numSlots + 1;
            boolean snake = !"linear".equals(draft != null ? draft.getType() : null);
            int onClockSlot = (snake && round % 2 == 0) ? numSlots - posInRound + 1 : posInRound;
            String label = slotGuess.get(onClockSlot);
            onClock = new LinkedHashMap<>();
            onClock.put("pickNo", pickNumber);
            onClock.put("round", round);
            onClock.put("slot", onClockSlot);
            onClock.put("label", label);
            onClock.put("isMine", "You".equals(label));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", draft != null ? draft.getStatus() : "unknown");
        result.put("rounds", totalRounds);
        result.put("numSlots", numSlots);
        result.put("slotHeaders", slotHeaders);
        result.put("onClock", onClock);
        result.put("picks", allPicks);
        result.put("recentPicks", lastTen);
        result.put("suggestions", buildSuggestions(draftedPlayerIds));
        result.put("linkedToLeague", league != null);
        return result;
    }

    /** Available players ranked by our own projection (already ADP-blended, see Guardrail 5), with raw ADP shown alongside. */
    private List<Map<String, Object>> buildSuggestions(Set<String> draftedPlayerIds) {
        Map<String, Double> adp = fantasyCalculatorService.currentAdp(12);

        List<PlayerPrediction> predictions = predictionRepository.findByPredictedSeasonWithPlayer(TARGET_SEASON);
        predictions.sort(Comparator.comparingDouble((PlayerPrediction pp) ->
                pp.getProjectedPoints() != null ? pp.getProjectedPoints() : -1.0).reversed());

        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (PlayerPrediction pp : predictions) {
            if (suggestions.size() >= SUGGESTION_LIMIT) break;
            Player p = pp.getPlayer();
            if (p.getExternalId() != null && draftedPlayerIds.contains(p.getExternalId())) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", p.getFullName());
            row.put("position", p.getPosition());
            row.put("team", p.getTeam() != null ? p.getTeam().getCode() : null);
            row.put("projectedPoints", pp.getProjectedPoints());
            row.put("adp", adp.get(NameUtil.key(p.getFullName(), p.getPosition())));
            suggestions.add(row);
        }
        return suggestions;
    }
}
