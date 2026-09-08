# Week-by-Week Projections — Plan & Task List

Living doc. Goal: per-player, per-week PPR projections for the current season, surfaced on the
league page, player profiles, and a league player-search / waiver view, plus weekly fantasy
matchups with a win %.

Season projections (`PlayerPrediction`, one number per player per season) already exist and stay
as the **prior** the weekly layer adjusts. `serve.py`'s `/predict/week` is a stub today.

---

## Data sources

| Signal | Source | Status |
|---|---|---|
| Weekly box scores (points, snaps, targets, rush att, opp) | `PlayerWeeklyStat` (2020–2025) | have; needs current-season weekly sync |
| Schedule (who plays whom, home/away, kickoff) | `cdn.espn.com/core/nfl/schedule` | have (used for byes) |
| **Betting odds (spread, O/U, → implied team total)** | **same `cdn.espn.com` schedule feed — `competitions[].odds[]` carries `spread` / `overUnder` / provider** | verified present for 2026; **no separate odds API needed** |
| Injury status / body part / practice participation | Sleeper `/players/nfl` (`injury_status`, `injury_body_part`, `practice_participation`) | have `injury_status` via `currentInjuryStatuses()`; practice fields only populate in-season |
| Depth chart order | Sleeper `/players/nfl` (`depth_chart_order`) via `currentDepthChartOrders()` | have |
| Bye weeks | `byeWeeksByTeam()` | have |
| League matchups / live scores | Sleeper `GET /league/{id}/matchups/{week}` | not wired |
| Roster adds/drops / trades | Sleeper `GET /league/{id}/transactions/{week}`; `syncLeague` already clears+rebuilds rosters on re-sync | re-sync works; not scheduled, no "recent moves" feed |
| Available vs rostered player universe | Sleeper `/players/nfl` minus union of `FantasyRosterPlayer` for the league | derivable, not built |
| Weather (dome/wind/precip) | TBD (nice-to-have, K + passing) | not planned yet |

---

## The four requested model inputs, mapped

1. **Team matchup** → opponent defense-vs-position (DvP) points allowed, rolling season-to-date +
   prior-season blend; home/away; **implied team total** from ESPN odds; team plays-per-game / pace.
2. **Usage rate** → rolling L3/L4-week snap %, target share, rush-attempt share, red-zone touches
   from `PlayerWeeklyStat`; `depth_chart_order` as the role signal. Weeks 1–3 fall back to the
   season projection + depth chart + prior year.
3. **Injury status** → `injury_status` + `practice_participation` + `depth_chart_order`. Q = small
   discount, D/O/IR/PUP = zero it / heavy discount. **Backup bump**: starter OUT → same-team
   next-man-up at that position gets a usage boost (needs same-team depth awareness).
4. **Past weekly averages vs similar matchups** → opponent DvP tier/rank as a feature; either feed
   `opp_dvp_allowed` continuously and let the model learn, or bucket into tiers and use the
   player's historical PPG vs that tier.

---

## Modeling approach

**v1 — adjustment model (ship first, no new ML):**
`weekly = (season_projection / 17) × matchup_mult × usage_mult × injury_mult × home_mult`, clamped.
Each multiplier heuristic/lookup. Transparent, degrades gracefully, gets the whole data +
UI pipeline built. Compute in Java (mostly lookups) or fill in `/predict/week`.

**v2 — per-position weekly GBM:**
Train on player-week rows (~30k vs a few hundred for the season model). Features: rolling usage,
opp DvP, implied team total, home/away, days rest, `depth_chart_order`, season projection as a
feature, position. Target = that week's PPR. Walk-forward eval **by week**. Swap in behind the
same `PlayerWeeklyPrediction` interface.

**Both:** also emit **floor / ceiling** (L4 weekly std, or quantile GBM) — start/sit decisions
live in the range, not the point estimate.

---

## Win probability

- **Fantasy matchup win %** (your team vs opponent's team, week N): Monte Carlo — sample each
  player's weekly score from `projection` + variance, sum per lineup, 10k sims, count wins. Needs
  only the weekly projections + a variance estimate. This is the useful one for a league tool.
- **NFL game win %** (for context / DST): from the ESPN spread — `P(win) ≈ Φ(-spread / 13.5)` or a
  logistic fit on historical spread→result.

---

## New / changed persistence

- `PlayerWeeklyPrediction` — `(sleeper_player_id, season, week)` unique; `projected_points`,
  `floor`, `ceiling`, `opp_code`, `is_home`, `implied_total`, `model_version`, `computed_at`.
  Key by sleeper id (not season-indexed `Player.id`), like `FantasyRosterPlayer`.
- `FantasyMatchup` — `(league_id, week, roster_id)`; `matchup_id`, `points`, `projected_points`,
  `win_prob`. Synced from Sleeper matchups endpoint.
- `TeamWeeklyContext` (optional cache) — `(season, week, team_code)`; `opp_code`, `is_home`,
  `implied_total`, `spread`, `over_under`, `dvp_allowed_qb/rb/wr/te`. Avoids per-player recompute.
- `FantasyLeague.rostersLastSyncedAt` (+ show "synced X ago" on `/league/{id}`).
- (v3) `WeeklyProjectionAccuracy` — projected vs actual per player-week, for MAE tracking + eval.

---

## Sync jobs (today everything is a manual admin button)

- **Sync current NFL week**: `SleeperService.syncWeek(year, week, "regular")` + opponent codes
  already exist and `/admin/sync-week` is wired — need a scheduler (Spring `@Scheduled`, ~Tue AM)
  or a "sync live week" button that auto-targets the current week.
- **League roster re-sync**: `syncLeague` already picks up waiver adds/drops on re-sync — schedule
  it (daily) and stamp `rostersLastSyncedAt`.
- **Matchups + odds**: pull Sleeper `matchups/{week}` and ESPN odds (Thu), store.
- **Recompute weekly projections**: after the above, for the current + next 1–2 weeks.
- Laptop caveat: `@Scheduled` needs the app always-running; keep manual buttons as the fallback.

---

## UX surfaces

- **Player profile `/players/{id}`** — new "Weekly Projections" section: week 1–18 table / bar
  chart, projected + floor/ceiling, opponent, H/A, implied total, BYE rows.
- **League detail `/league/{id}`** — week selector; each rostered player's Week N projection in the
  roster panel (built already); team projected total; matchup line (vs opp team, both totals,
  win %); flag bench players out-projecting starters (start/sit).
- **League player search `/league/{id}/players`** — searchable list of all fantasy-relevant
  players: position, NFL team, this-week projection, rest-of-season projection, ADP / consensus,
  injury badge, and **Rostered by [team] / Available**. Filters: position, availability, NFL team.
  This is the waiver-wire assistant.
- **Matchup view `/league/{id}/matchup?week=N`** — your lineup vs opponent's, side by side,
  per-player weekly projections, totals, win %.

---

## Extra items worth adding (not in the original ask)

- **Bye-week handling** — project 0 / "BYE", exclude from team totals and win-prob sims.
- **Floor / ceiling range** — see modeling; needed for start/sit.
- **Backup-bump when a starter is OUT** — high value, needs same-team depth modeling.
- **League scoring settings** — read Sleeper `league.scoring_settings`; weekly numbers are PPR-only
  today. At minimum detect half-PPR / standard and scale, or note the mismatch. (CLAUDE.md Future
  Work #6.)
- **"Projection stale" indicator** — current week not synced yet, or player status changed after
  the projection was computed.
- **Transactions feed** — Sleeper `transactions/{week}` → "recent moves" panel; also tells you
  *when* rosters changed.
- **Historical accuracy page** — projected vs actual, weekly MAE; earns trust and doubles as eval.
- **Games-this-week / matchup finder** — from the schedule feed: all week-N games + kickoff times,
  group a roster by game (early / late / SNF / MNF), show which players have already played.
- **Weather** — dome/wind/precip for K and passing (needs a source; low priority).
- **K / DST weekly** — mostly implied total + opp DvP; a simple matchup lookup, no GBM needed.

---

## Open questions / risks

- ESPN odds: are `competitions[].odds[]` reliably populated for every game a few days out? (Looked
  good for 2026 wk 2 via DraftKings.) If a game is missing odds, fall back to no-implied-total.
- Weekly data latency: Sleeper posts a week's stats ~Tue after MNF. Project Wed–Thu once
  inactives/practice reports firm up; re-project Sun AM.
- Rolling usage needs ≥3 weeks to mean anything — weeks 1–3 lean on the season prior + depth chart.
- Keying: weekly predictions by `sleeper_player_id + season + week`, resolve to `Player` for
  display only (avoids the season-indexed join headaches).
- Compute cost is trivial (~600 players × 18 wks ≈ 11k rows per full recompute).

---

## Checklist

### Phase 0 — data plumbing
- [ ] Extend `fetchEspnScheduleForWeek` (or a sibling) to also return `spread` / `overUnder` per
      team → implied team totals.
- [ ] `SleeperService.leagueMatchups(leagueId, week)` + `FantasyMatchup` entity + sync.
- [ ] Scheduled / one-button "sync current NFL week" (weekly stats + opponent codes).
- [ ] Scheduled daily league roster re-sync; `FantasyLeague.rostersLastSyncedAt` + show it.
- [ ] Rolling-usage helper: L3/L4 snap %, target share, rush share, RZ touches from `PlayerWeeklyStat`.
- [ ] Rolling DvP helper: PPR allowed to each position by each defense, season-to-date.
- [ ] `PlayerWeeklyPrediction` entity + repo.

### Phase 1 — v1 weekly projection (adjustment model)
- [ ] `weekly = season_proj/17 × matchup × usage × injury × home`, clamped; BYE → 0.
- [ ] Injury multiplier from `injury_status` + `practice_participation` + `depth_chart_order`.
- [ ] Floor / ceiling from L4 weekly std.
- [ ] Implement `/predict/week` (or compute in Java) + persist `PlayerWeeklyPrediction`.
- [ ] Admin: "Compute weekly projections — week N".

### Phase 2 — UX
- [ ] Player profile: weekly projection section (table + sparkline), wk 1–18, opp, H/A, range.
- [ ] League detail: week selector; per-player weekly proj in the roster panel; team total.
- [ ] League detail: matchup line — vs opp team, both projected totals, win %.
- [ ] `/league/{id}/players` search page: position / availability / team filters, rostered-by,
      this-week + RoS projection, injury, ADP/consensus.
- [ ] `/league/{id}/matchup?week=N`: side-by-side lineups, start/sit flags.

### Phase 3 — real weekly model
- [ ] Per-position weekly GBM on player-week rows; features per "modeling approach" above.
- [ ] Walk-forward eval by week; accuracy vs the v1 adjustment model.
- [ ] Backup-bump modeling (starter OUT → next man up).
- [ ] `WeeklyProjectionAccuracy` tracking + a "model accuracy" page.

### Phase 4 — polish
- [ ] Fantasy matchup win % via Monte Carlo (10k sims from projections + variance).
- [ ] League scoring settings (half-PPR / standard) applied to weekly numbers.
- [ ] Transactions feed ("recent moves").
- [ ] "Projection stale" indicators.
- [ ] Games-this-week / matchup finder view.
- [ ] Weather (K + passing).
