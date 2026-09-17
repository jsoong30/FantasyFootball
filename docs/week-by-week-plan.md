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
- [x] `FantasyLeague.rostersLastSyncedAt` (+ show "synced X ago" on `/league` and `/league/{id}`).
  Also added `FantasyTeam.pointsFor`/`pointsAgainst` (Sleeper `fpts`/`fpts_against`) alongside it —
  not originally scoped here, but the same sync pass and needed for "scoring" freshness too.
- (v3) `WeeklyProjectionAccuracy` — projected vs actual per player-week, for MAE tracking + eval.

---

## Automation & data freshness

Today **everything is a manual admin button** and several things are hardcoded. This makes
rankings / stats / league / projections silently drift mid-season. Fixes, by priority.

### P0 — foundational, small, unblocks the rest

- [x] **`SeasonConfig` bean** (`application.yml` props, `app.season.source-season` /
      `app.season.target-season`, overridable via `SOURCE_SEASON`/`TARGET_SEASON` env vars) to
      replace the hardcoded `TARGET_SEASON = 2026` / `SOURCE_SEASON = 2025` copy-pasted in
      `PredictionsController`, `HomeController`, `DraftController`. One place to roll the season.
- [x] **Current season/week from Sleeper `/state/nfl`** (keyless: `{season, week, season_type}`) —
      `SleeperService.currentNflState()`, 15-min cached. Surfaced on `/admin/sync` next to the
      `SeasonConfig` values so a stale `app.season.*` (season rolled but config not updated) is
      visible at a glance. Not yet consumed by a scheduled job — no such job exists yet; wire it in
      when the P1 "Weekly season sync" job below is built.
- [x] **`@EnableScheduling` + a scheduler bean**, gated by `app.scheduler.enabled` (default
      **true** — deliberately on, not off, since this is a real single-user deploy, not a shared
      dev box several people might run locally; flip to `false` via env var if that changes).
      `LeagueSyncScheduler` is the first job; its `syncAllLeaguesNow()` is also the
      `POST /admin/sync-leagues` button — cron and button call the same code. Note: the per-league
      work is called as a genuine cross-bean call into `FantasyLeagueService.syncLeague()`, not
      from a method living on `FantasyLeagueService` itself — a self-invoked call to a
      `@Transactional` method skips Spring's proxy and its transaction, which would both defeat
      per-league failure isolation and (worse) throw `LazyInitializationException` on this
      scheduler thread (no Open-Session-In-View outside an HTTP request). Keep this in mind for
      any future scheduled job that loops and calls back into a `@Transactional` service method.

### P1 — high value: keeps rankings / stats / projections current

- [ ] **Weekly season sync** — Tue ~6am ET, run the full **`syncSeason(currentSeason)`**, NOT the
      partial `syncWeek`. `syncSeason` is idempotent (~1–3 min; unplayed weeks return empty and
      skip) and it's the only path that recomputes `PlayerStat` season totals **and**
      `assignRanks`. Keep `syncWeek` only for the preseason dry-run it was built for. (Still not
      done — `LeagueSyncScheduler` below only covers league standings/rosters, not player stats.)
      Make sure that all of the stat displays are updated, not just the stats page. Even the players pages.
- [x] **League roster + scoring sync** — `LeagueSyncScheduler` (`@Scheduled`, cron
      `app.scheduler.league-sync-cron`, default **Tuesday 7am**) calls its own
      `syncAllLeaguesNow()`, which re-syncs *every* league for *every* user:
      standings (wins/losses/ties), scoring (`pointsFor`/`pointsAgainst`), rosters (picks up
      waiver adds/drops — `syncLeague` already clears+rebuilds), and league status.
      `rostersLastSyncedAt` stamped + shown as "synced X ago". Went with **weekly**, not nightly —
      standings/scoring only change once a week's games finish anyway; cadence is a one-line
      cron-property change (`LEAGUE_SYNC_CRON` env var) if daily ever matters more.
- [ ] **Weekly re-run predictions** — `syncPredictions(source, target)` with **no retrain**. The
      season *model* barely moves week to week, but Guardrails 3–5 (status discount, depth-chart
      nudge, ADP/FantasyPros blend) use *live* data, so a weekly re-predict keeps situational
      adjustments (Gibbs/Bijan-style) fresh for free.
- [ ] **`POST /reload-models` on `serve.py`** — `uvicorn --reload` watches `.py`, not `.pkl`, so a
      retrain currently needs a manual server bounce. A 5-line endpoint re-running `_load_models()`
      removes that step and lets a future retrain job chain cleanly.
- [ ] **Persist ranks that are currently on-the-fly / missing**: positional rank on `PlayerStat`
      (compute in `assignRanks`; `PlayersController.detail` recomputes it every request), and a
      `rank` column on `PlayerWeeklyStat` (rank within that week, set during sync — feeds the
      weekly model's usage features and "boom week" views).
- [ ] **Auto-write `ml/data/fantasy_stats_all.csv` + `fantasy_weekly_all.csv`** as part of the
      weekly sync job (reuse the export logic, write to file instead of HTTP response), so a
      retrain is always just `py train.py`. **Do not auto-retrain the season model** — eyeballing
      the walk-forward eval MAE before trusting a new model is a judgment step. (The *weekly*
      model, once it exists, is the thing that should retrain on a schedule.)

### P2 — later / nice-to-have

- [ ] **Projected-vs-actual logging** each week (baseline: season projection prorated to /17) →
      in-season MAE. Same `WeeklyProjectionAccuracy` table the weekly model's Phase 3 needs.
- [ ] **Retry + surface failures** — Sleeper posts a week's stats ~Tue after MNF; if the sync
      returns "No stats available yet", retry a few hours later. Show last-run status per job on
      `/admin/sync` (a `SyncRun`/`SyncLog` row, or at least timestamps).
- [ ] **Matchups + odds job** — Thu, pull Sleeper `matchups/{week}` + ESPN odds, store. (Also a
      Phase 0 item for the weekly-projection feature.)
- [ ] **Weekly projection recompute job** — after the sync + matchups land, for the current +
      next 1–2 weeks. (Depends on the weekly model existing.)
- **Reliability caveat**: unattended `@Scheduled` needs the app (and Docker Postgres, and uvicorn
  for predict) always up — really wants a small always-on host, not a laptop. Decide this before
  leaning on the scheduler; the manual buttons stay as the fallback either way.

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
> The sync automation (weekly season sync, nightly roster sync, `SeasonConfig`, `/state/nfl`,
> matchups+odds job) lives in **Automation & data freshness** above — do P0/P1 there first.
- [ ] Extend `fetchEspnScheduleForWeek` (or a sibling) to also return `spread` / `overUnder` per
      team → implied team totals.
- [ ] `SleeperService.leagueMatchups(leagueId, week)` + `FantasyMatchup` entity + sync.
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
