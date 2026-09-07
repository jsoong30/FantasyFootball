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
import com.firstember.fantasyfootball.sleeper.SleeperService;
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
    private static final int TOP_PICK_COUNT = 3;
    // Softmax temperature (in projected-PPR points) for spreading the top picks' composite
    // scores into confidence percentages — smaller values produce more decisive percentage gaps.
    private static final double CONFIDENCE_TEMPERATURE = 20.0;

    // Standard PPR starting lineup used for roster-slot display (see buildMyRoster) and as the
    // basis for the need curve below. Not pulled from Sleeper's league settings (roster_positions)
    // — this app doesn't fetch/store per-league roster construction, and a standalone mock draft
    // has no league to pull it from anyway, so a single reasonable default is used everywhere.
    private static final List<String> STARTER_SLOT_TEMPLATE =
            List.of("QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "FLEX", "DST", "K");
    private static final Set<String> FLEX_ELIGIBLE_POSITIONS = Set.of("RB", "WR", "TE");

    // Need-factor curve per position: NEED_CURVE.get(pos)[have] is the multiplier applied to a
    // player's projected points when the viewer already has `have` players at that position
    // (indices beyond the array's end reuse the last value). Deliberately NOT a smooth formula —
    // single-starter positions (QB/TE/K/DST) fall off a cliff after the first pick since a
    // backup there is low value (streamed/matchup-only), while RB/WR taper gradually since real
    // rosters want 4-5 of each for bye weeks, injury/trade insurance, and flex eligibility.
    private static final Map<String, double[]> NEED_CURVE = Map.of(
            "QB",  new double[]{1.5, 0.35, 0.2},
            "RB",  new double[]{1.6, 1.45, 1.3, 1.15, 1.0, 0.55, 0.35},
            "WR",  new double[]{1.6, 1.45, 1.3, 1.15, 1.0, 0.55, 0.35},
            "TE",  new double[]{1.4, 0.35, 0.2},
            "K",   new double[]{1.2, 0.2},
            "DST", new double[]{1.2, 0.2}
    );

    // Flat scarcity discount applied on top of the need curve, independent of roster fill state.
    // 1.0 = baseline (RB — the scarce premium position: bell-cow backfields are few and RB
    // production drops off a cliff, so an RB is hard to replace once the top tier is gone).
    //  - WR (0.92): a mild discount vs RB. The viable-starter pool is deeper — the gap between
    //    WR12 and WR30 is far smaller than RB12→RB30 — so an RB and a WR with the same
    //    projection are not equally valuable on the clock.
    //  - QB (0.65): discounted hard. Real draft market (ADP) values QB far below its raw point
    //    total in single-QB formats — Josh Allen is the clear QB1 by a wide margin but still
    //    goes ~pick 29, because a streamed QB10 isn't far behind.
    //  - K / DST (0.30): the "draft them last" positions. Their raw point totals overlap mid-
    //    round RB/WR (a top K ~170, top DST ~180), but week to week the K1-vs-K15 and DST1-vs-
    //    DST15 gap is almost pure matchup noise, so they're the most replaceable slots on the
    //    board — this discount keeps them out of the suggestions until the final rounds, where
    //    the need curve's 1.2 factor then floats them to the top of what's left. (The need curve
    //    only ever de-prioritised a *second* K/DST; nothing pushed the first one late.)
    // Deliberately heuristic knobs — nudge them, don't over-fit. Positions not listed = 1.0.
    private static final Map<String, Double> POSITION_SCARCITY_WEIGHT = Map.of(
            "QB",  0.65,
            "WR",  0.92,
            "K",   0.30,
            "DST", 0.30
    );

    // ── One-off custom draft-order override ───────────────────────────────────────────────────
    // This league's Sleeper draft reshuffles the pick order every 2 rounds: it snakes within
    // each 2-round block, then draws a fresh random order for the next block. Sleeper's API
    // exposes only round 1 (draft_order / slot_to_roster_id) — the per-block reshuffle is
    // internal to Sleeper's engine and isn't in the draft object or its settings — so the order
    // below was transcribed by hand from the live Sleeper draft board.
    //
    // Scope: display only. Which team a *made* pick belongs to is still resolved from that pick's
    // own roster_id (see the picks loop), never from this table. This only fixes the forward-
    // looking bits the pure-snake math gets wrong from round 3 on: who's on the clock, the
    // "YOUR PICK" flag, the viewer's upcoming-picks list, and undrafted grid-cell labels.
    //
    // Values are ROUND-1 SEAT NUMBERS (1..12) in pick order. Only the odd rounds are stored;
    // each even round is the exact reverse of the odd round before it. Rounds past the last key
    // fall back to plain snake. Delete this block + its helper + call sites after the draft.
    private static final String   CUSTOM_ORDER_DRAFT_ID = "1382895987483230208";
    private static final int      CUSTOM_ORDER_SEATS     = 12;
    private static final int      CUSTOM_ORDER_MY_SEAT   = 6;   // Jsoong30
    private static final String[] CUSTOM_ORDER_SEAT_NAMES = {
            "NO MERCY", "Breeces Pieces", "cstarkey", "Get off my Ditka", "Jiggins", "Jsoong30",
            "Girlz Rule!", "drock284", "Brady's Bunch", "Gronkey Kong", "Inflamed Jockitch", "gsoong1"
    };
    private static final Map<Integer, int[]> CUSTOM_ORDER_ODD_ROUNDS = Map.of(
            1,  new int[]{ 1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12},
            3,  new int[]{11, 12,  2,  9,  7,  3,  4,  8, 10,  5,  6,  1},
            5,  new int[]{10,  6, 12,  3, 11,  5,  8,  4,  9,  2,  1,  7},
            7,  new int[]{ 4,  9,  8,  6,  2,  7, 11,  1, 12,  5,  3, 10},
            9,  new int[]{ 3,  5,  2,  9, 12,  6,  1, 11,  8, 10,  4,  7},
            11, new int[]{ 8,  7, 12,  3, 11,  9, 10,  6,  4,  1,  5,  2},
            13, new int[]{ 5,  3,  9,  6,  1,  8,  4,  2, 12,  7, 11, 10},
            15, new int[]{ 5,  9,  7,  3,  2,  6, 10,  8,  4, 11, 12,  1}
    );

    /**
     * Custom pick order for {@code round} as an array of round-1 seat numbers (index 0 = first
     * pick of the round), or {@code null} when the override doesn't apply (different draft,
     * non-12-team, or a round beyond the transcribed data — caller falls back to snake).
     */
    private static int[] customRoundOrder(String draftId, int round, int seats) {
        if (!CUSTOM_ORDER_DRAFT_ID.equals(draftId) || seats != CUSTOM_ORDER_SEATS) return null;
        if (round % 2 == 1) return CUSTOM_ORDER_ODD_ROUNDS.get(round);
        int[] prev = CUSTOM_ORDER_ODD_ROUNDS.get(round - 1);
        if (prev == null) return null;
        int[] reversed = new int[prev.length];
        for (int i = 0; i < prev.length; i++) reversed[i] = prev[prev.length - 1 - i];
        return reversed;
    }

    private final DraftService draftService;
    private final FantasyLeagueRepository leagueRepository;
    private final FantasyTeamRepository teamRepository;
    private final PlayerPredictionRepository predictionRepository;
    private final FantasyCalculatorService fantasyCalculatorService;
    private final SleeperService sleeperService;

    public DraftController(DraftService draftService,
                           FantasyLeagueRepository leagueRepository,
                           FantasyTeamRepository teamRepository,
                           PlayerPredictionRepository predictionRepository,
                           FantasyCalculatorService fantasyCalculatorService,
                           SleeperService sleeperService) {
        this.draftService = draftService;
        this.leagueRepository = leagueRepository;
        this.teamRepository = teamRepository;
        this.predictionRepository = predictionRepository;
        this.sleeperService = sleeperService;
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

        Map<String, Integer> byeWeeks = sleeperService.byeWeeksByTeam(TARGET_SEASON);

        // Live injury designations (Questionable / Out / IR / ...), keyed by Sleeper player id.
        // Cached 5 min in SleeperService, so this is cheap on the 4s poll. Cosmetic — degrade to
        // an empty map on any failure rather than dropping the whole board response.
        Map<String, SleeperService.InjuryStatus> injuries;
        try {
            injuries = sleeperService.currentInjuryStatuses();
        } catch (Exception e) {
            injuries = Map.of();
        }

        Set<String> draftedPlayerIds = new HashSet<>();
        // Name-keyed drafted set too -- ADP-only suggestion rows (rookies, see buildSuggestions)
        // have no Sleeper id in our data, so they can only be filtered out by name once drafted.
        Set<String> draftedNameKeys = new HashSet<>();
        List<Map<String, Object>> allPicks = new ArrayList<>();

        for (SleeperDraftPickDTO p : picks) {
            if (p.getPlayerId() == null) continue;
            draftedPlayerIds.add(p.getPlayerId());
            if (p.getDisplayName() != null && p.getPosition() != null) {
                String pos = "DEF".equals(p.getPosition()) ? "DST" : p.getPosition();
                draftedNameKeys.add(NameUtil.key(p.getDisplayName(), pos));
            }

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

            String nflTeam = sleeperService.normalizeSleeperTeamCode(p.getTeamCode());
            SleeperService.InjuryStatus inj = injuries.get(p.getPlayerId());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("round", p.getRound());
            row.put("slot", p.getDraftSlot());
            row.put("pickNo", p.getPickNo());
            row.put("name", p.getDisplayName());
            row.put("position", p.getPosition());
            row.put("team", teamLabel);
            row.put("isMine", mine);
            row.put("nflTeam", nflTeam);
            row.put("byeWeek", nflTeam != null ? byeWeeks.get(nflTeam) : null);
            row.put("injuryStatus", inj != null ? inj.designation() : null);
            row.put("injuryBodyPart", inj != null ? inj.bodyPart() : null);
            allPicks.add(row);
        }

        // Tally the viewer's own roster-so-far by position, to weight suggestions toward
        // unfilled starter slots instead of just raw projection. Sleeper's draft-pick metadata
        // reports "DEF" for defenses like everywhere else in Sleeper's API; normalize to "DST"
        // to match Player.position (see SleeperService) before counting.
        Map<String, Integer> myPositionCounts = new HashMap<>();
        List<Map<String, Object>> myTeam = new ArrayList<>();
        for (Map<String, Object> row : allPicks) {
            if (!Boolean.TRUE.equals(row.get("isMine"))) continue;
            String pos = (String) row.get("position");
            if (pos == null) continue;
            if ("DEF".equals(pos)) pos = "DST";
            myPositionCounts.merge(pos, 1, Integer::sum);

            Map<String, Object> myRow = new LinkedHashMap<>();
            myRow.put("name", row.get("name"));
            myRow.put("position", pos);
            myRow.put("nflTeam", row.get("nflTeam"));
            myRow.put("byeWeek", row.get("byeWeek"));
            myRow.put("round", row.get("round"));
            myRow.put("slot", row.get("slot"));
            myRow.put("pickNo", row.get("pickNo"));
            myRow.put("injuryStatus", row.get("injuryStatus"));
            myRow.put("injuryBodyPart", row.get("injuryBodyPart"));
            myTeam.add(myRow);
        }
        myTeam.sort(Comparator.comparingInt((Map<String, Object> r) -> (Integer) r.get("pickNo")));

        List<Map<String, Object>> recentPicks = new ArrayList<>(allPicks);
        recentPicks.sort((a, b) -> ((Integer) b.get("pickNo")).compareTo((Integer) a.get("pickNo")));
        List<Map<String, Object>> lastTen = recentPicks.subList(0, Math.min(10, recentPicks.size()));

        List<String> slotHeaders = new ArrayList<>();
        for (int slot = 1; slot <= numSlots; slot++) slotHeaders.add(slotGuess.get(slot));

        // "On the clock" -- the SLOT due next is always deterministic (snake traversal of column
        // positions 1..N, alternating direction each round), even in a re-scrambled league, since
        // it's only the *team occupying* a slot that changes, not the traversal pattern. The team
        // LABEL is normally a best-effort header guess; when the custom-order override applies it's
        // the real team for that pick (and drives the "YOUR PICK" flag) — see customRoundOrder.
        int[] round1CustomOrder = customRoundOrder(draftId, 1, numSlots);
        Map<String, Object> onClock = null;
        if (numSlots > 0 && draftedPlayerIds.size() < (long) numSlots * totalRounds) {
            int pickNumber = draftedPlayerIds.size() + 1;
            int round = (pickNumber - 1) / numSlots + 1;
            int posInRound = (pickNumber - 1) % numSlots + 1;
            boolean snake = !"linear".equals(draft != null ? draft.getType() : null);
            int onClockSlot = (snake && round % 2 == 0) ? numSlots - posInRound + 1 : posInRound;

            int[] customOrder = customRoundOrder(draftId, round, numSlots);
            String label;
            boolean mineOnClock;
            if (customOrder != null) {
                int seat = customOrder[posInRound - 1];              // round-1 seat on the clock
                label = CUSTOM_ORDER_SEAT_NAMES[seat - 1];
                mineOnClock = seat == CUSTOM_ORDER_MY_SEAT;
            } else {
                label = slotGuess.get(onClockSlot);
                mineOnClock = "You".equals(label);
            }
            onClock = new LinkedHashMap<>();
            onClock.put("pickNo", pickNumber);
            onClock.put("round", round);
            onClock.put("slot", onClockSlot);
            onClock.put("label", label);
            onClock.put("isMine", mineOnClock);
        }

        // The viewer's own upcoming picks (custom-order drafts only — pure pick-count arithmetic,
        // independent of how Sleeper numbers draft_slot, so it's safe even mid-reshuffle).
        List<Map<String, Object>> upcomingMyPicks = new ArrayList<>();
        if (round1CustomOrder != null) {
            int made = draftedPlayerIds.size();
            for (int pn = made + 1; pn <= numSlots * totalRounds && upcomingMyPicks.size() < 6; pn++) {
                int r = (pn - 1) / numSlots + 1;
                int pos = (pn - 1) % numSlots + 1;
                int[] ord = customRoundOrder(draftId, r, numSlots);
                if (ord == null) break;                              // past the transcribed rounds
                if (ord[pos - 1] == CUSTOM_ORDER_MY_SEAT) {
                    Map<String, Object> mp = new LinkedHashMap<>();
                    mp.put("pickNo", pn);
                    mp.put("round", r);
                    mp.put("posInRound", pos);
                    upcomingMyPicks.add(mp);
                }
            }
        }

        // Planned team per undrafted grid cell (round -> team name by COLUMN 1..N). Column c maps
        // to pos-in-round via the snake (odd rounds ascend, even descend), then to a seat via the
        // override. Still rendered dimmed client-side — it's a plan, not a made pick.
        Map<Integer, List<String>> plannedOrder = new LinkedHashMap<>();
        if (round1CustomOrder != null) {
            for (int r = 1; r <= totalRounds; r++) {
                int[] ord = customRoundOrder(draftId, r, numSlots);
                if (ord == null) continue;
                List<String> byColumn = new ArrayList<>();
                for (int c = 1; c <= numSlots; c++) {
                    int pos = (r % 2 == 1) ? c : numSlots - c + 1;
                    byColumn.add(CUSTOM_ORDER_SEAT_NAMES[ord[pos - 1] - 1]);
                }
                plannedOrder.put(r, byColumn);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", draft != null ? draft.getStatus() : "unknown");
        result.put("rounds", totalRounds);
        result.put("numSlots", numSlots);
        result.put("slotHeaders", slotHeaders);
        result.put("onClock", onClock);
        result.put("upcomingMyPicks", upcomingMyPicks);
        result.put("plannedOrder", plannedOrder);
        result.put("picks", allPicks);
        result.put("recentPicks", lastTen);
        result.put("myTeam", buildMyRoster(myTeam));
        List<Map<String, Object>> suggestions = buildSuggestions(draftedPlayerIds, draftedNameKeys, myPositionCounts, byeWeeks, injuries);
        result.put("suggestions", suggestions);
        result.put("topPicks", buildTopPicks(suggestions));
        result.put("linkedToLeague", league != null);
        return result;
    }

    /**
     * Available players ranked by a composite score: our own projection (already ADP-blended,
     * see Guardrail 5) scaled by how much the viewer's own roster still needs that position.
     * Raw projection/ADP are still shown alongside so the ranking isn't a black box.
     */
    private List<Map<String, Object>> buildSuggestions(Set<String> draftedPlayerIds, Set<String> draftedNameKeys,
                                                         Map<String, Integer> myPositionCounts,
                                                         Map<String, Integer> byeWeeks,
                                                         Map<String, SleeperService.InjuryStatus> injuries) {
        Map<String, Double> adp = fantasyCalculatorService.currentAdp(12);

        List<PlayerPrediction> predictions = predictionRepository.findByPredictedSeasonWithPlayer(TARGET_SEASON);

        List<Map<String, Object>> suggestions = new ArrayList<>();
        Set<String> haveProjection = new HashSet<>();   // name-keys we already have a prediction row for
        for (PlayerPrediction pp : predictions) {
            Player p = pp.getPlayer();
            haveProjection.add(NameUtil.key(p.getFullName(), p.getPosition()));
            if (p.getExternalId() != null && draftedPlayerIds.contains(p.getExternalId())) continue;

            double projectedPoints = pp.getProjectedPoints() != null ? pp.getProjectedPoints() : 0.0;
            int have = myPositionCounts.getOrDefault(p.getPosition(), 0);
            double need = needFactor(p.getPosition(), have);
            double scarcity = POSITION_SCARCITY_WEIGHT.getOrDefault(p.getPosition(), 1.0);
            String teamCode = p.getTeam() != null ? p.getTeam().getCode() : null;
            SleeperService.InjuryStatus inj = p.getExternalId() != null ? injuries.get(p.getExternalId()) : null;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", p.getFullName());
            row.put("position", p.getPosition());
            row.put("team", teamCode);
            row.put("projectedPoints", pp.getProjectedPoints());
            row.put("adp", adp.get(NameUtil.key(p.getFullName(), p.getPosition())));
            row.put("byeWeek", teamCode != null ? byeWeeks.get(teamCode) : null);
            row.put("compositeScore", projectedPoints * need * scarcity);
            row.put("needLabel", needLabel(need));
            row.put("injuryStatus", inj != null ? inj.designation() : null);
            row.put("injuryBodyPart", inj != null ? inj.bodyPart() : null);
            row.put("adpOnly", false);
            suggestions.add(row);
        }

        // ADP-only rows: players the market is drafting that we have no projection for -- most
        // notably the current rookie class (no prior NFL season to project from, no synced
        // TARGET_SEASON stats yet). Ranked by ADP alone via a pseudo-score that interleaves them
        // with real projections at roughly their ADP-implied value, then flagged so the UI can
        // show they're market-only, not model output.
        for (FantasyCalculatorService.AdpEntry e : fantasyCalculatorService.currentAdpEntries(12)) {
            String key = NameUtil.key(e.name(), e.position());
            if (haveProjection.contains(key) || draftedNameKeys.contains(key)) continue;
            if (!NEED_CURVE.containsKey(e.position())) continue;   // unknown position -- skip

            int have = myPositionCounts.getOrDefault(e.position(), 0);
            double need = needFactor(e.position(), have);
            double scarcity = POSITION_SCARCITY_WEIGHT.getOrDefault(e.position(), 1.0);
            // Conservative pseudo-projection from ADP (an unproven name shouldn't out-rank a
            // comparable player we actually have numbers on): elite ADP ~= a low-end starter.
            double pseudo = Math.max(20.0, 200.0 - e.adp());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", e.name());
            row.put("position", e.position());
            row.put("team", e.team());
            row.put("projectedPoints", null);
            row.put("adp", e.adp());
            row.put("byeWeek", e.team() != null ? byeWeeks.get(e.team()) : null);
            row.put("compositeScore", pseudo * need * scarcity);
            row.put("needLabel", needLabel(need));
            row.put("injuryStatus", null);
            row.put("injuryBodyPart", null);
            row.put("adpOnly", true);
            suggestions.add(row);
        }

        suggestions.sort(Comparator.comparingDouble((Map<String, Object> r) -> (Double) r.get("compositeScore")).reversed());
        return suggestions.size() > SUGGESTION_LIMIT ? suggestions.subList(0, SUGGESTION_LIMIT) : suggestions;
    }

    /**
     * Assigns the viewer's drafted players (already in pick order) into fixed roster slots —
     * {@link #STARTER_SLOT_TEMPLATE} in order, then overflow as bench — the way a real fantasy
     * roster fills in as you draft: each player takes their own position's next open slot, or an
     * open FLEX if their own slots are full and they're flex-eligible, or the bench otherwise.
     * Returns one row per slot (template slots first, always present even if empty, then one row
     * per bench player), each with a {@code slotLabel} and a {@code player} (null if unfilled).
     */
    private List<Map<String, Object>> buildMyRoster(List<Map<String, Object>> pickedPlayers) {
        Map<String, Object>[] assigned = new Map[STARTER_SLOT_TEMPLATE.size()];
        List<Map<String, Object>> bench = new ArrayList<>();

        for (Map<String, Object> player : pickedPlayers) {
            String pos = (String) player.get("position");
            int slotIndex = -1;
            for (int i = 0; i < STARTER_SLOT_TEMPLATE.size(); i++) {
                if (assigned[i] == null && STARTER_SLOT_TEMPLATE.get(i).equals(pos)) {
                    slotIndex = i;
                    break;
                }
            }
            if (slotIndex == -1 && FLEX_ELIGIBLE_POSITIONS.contains(pos)) {
                for (int i = 0; i < STARTER_SLOT_TEMPLATE.size(); i++) {
                    if (assigned[i] == null && "FLEX".equals(STARTER_SLOT_TEMPLATE.get(i))) {
                        slotIndex = i;
                        break;
                    }
                }
            }
            if (slotIndex != -1) {
                assigned[slotIndex] = player;
            } else {
                bench.add(player);
            }
        }

        List<Map<String, Object>> roster = new ArrayList<>();
        for (int i = 0; i < STARTER_SLOT_TEMPLATE.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slotLabel", STARTER_SLOT_TEMPLATE.get(i));
            row.put("player", assigned[i]);
            roster.add(row);
        }
        for (Map<String, Object> b : bench) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slotLabel", "BN");
            row.put("player", b);
            roster.add(row);
        }
        return roster;
    }

    /**
     * How urgently the viewer's roster wants another player at this position, as a multiplier
     * on projected points — looked up from {@link #NEED_CURVE}. Positions outside the map
     * (shouldn't happen — every drafted position is one of QB/RB/WR/TE/K/DST) are left unscaled.
     */
    private double needFactor(String position, int have) {
        double[] curve = NEED_CURVE.get(position);
        if (curve == null) return 1.0;
        return curve[Math.min(have, curve.length - 1)];
    }

    private String needLabel(double need) {
        if (need >= 1.2) return "High Need";
        if (need >= 0.9) return "Good Fit";
        if (need >= 0.5) return "Depth";
        return "Roster Full";
    }

    /**
     * The top {@link #TOP_PICK_COUNT} suggestions, annotated with a softmax-derived confidence
     * percentage over just that shortlist (a FantasyPros-style "who to draft" widget) — how
     * strongly the composite score favors each pick over the other two/three shown, not a
     * probability of anything actually happening.
     */
    private List<Map<String, Object>> buildTopPicks(List<Map<String, Object>> suggestions) {
        List<Map<String, Object>> top = suggestions.subList(0, Math.min(TOP_PICK_COUNT, suggestions.size()));
        double maxScore = top.stream().mapToDouble(r -> (Double) r.get("compositeScore")).max().orElse(0.0);
        double sumExp = top.stream().mapToDouble(r -> Math.exp(((Double) r.get("compositeScore") - maxScore) / CONFIDENCE_TEMPERATURE)).sum();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : top) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            double exp = Math.exp(((Double) row.get("compositeScore") - maxScore) / CONFIDENCE_TEMPERATURE);
            copy.put("confidencePct", Math.round(exp / sumExp * 1000.0) / 10.0);
            result.add(copy);
        }
        return result;
    }
}
