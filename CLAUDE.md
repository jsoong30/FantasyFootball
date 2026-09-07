# FantasyFootball — Agent Context

This file is the single source of truth for any AI agent working on this project.
Read it fully before making any changes.

---

## Project Overview

A full-stack Fantasy Football web app with an AI prediction layer.

- **Web app**: Spring Boot 3.5 + Thymeleaf, running on `localhost:8080`
- **Database**: PostgreSQL 16 via Docker, port `5433`, database `fantasydb`
- **ML pipeline**: Python (scikit-learn + FastAPI), running on `localhost:8000`
- **Data source**: [Sleeper API](https://docs.sleeper.com/) — free, no API key requireds

The app shows NFL player stats (2020–2025), ranks players by PPR fantasy points, and uses a
trained GradientBoosting model to project next-season fantasy points.

---

## Environment & Setup

### Prerequisites (already installed on this machine)
- Java 17 (Eclipse Temurin)
- Docker Desktop
- Python 3.12.6 — **use `py` not `python`, `py -m pip` not `pip`**
- Maven wrapper (`./mvnw` on Mac/Linux, `.\mvnw` on Windows)

### Database
PostgreSQL runs in Docker on port **5433** (not the default 5432):
```
docker compose up -d
```

DB credentials come from `.env` (gitignored) and `src/main/resources/application-local.yml` (gitignored).
`application.yml` uses `${POSTGRES_USER}` / `${POSTGRES_PASSWORD}` environment variables.
Hibernate DDL is set to `update` — it auto-creates and auto-migrates columns on startup.

`.env` is loaded into the Spring Boot process via `"envFile": "${workspaceFolder}/.env"` in `.vscode/launch.json`
(added when the FantasyPros integration needed a key) — it was previously only read by Docker Compose for Postgres.
`FANTASY_PROS_API` in `.env` maps to `${FANTASY_PROS_API:}` → `fantasypros.api.key` in `application.yml`. If this
env var is missing, `FantasyProsService` logs and returns an empty map rather than failing — the market-consensus
blend (Guardrail 5 in serve.py) is a nice-to-have, not load-bearing.

### Spring Boot
Run via VS Code Run & Debug panel with profile `local` (reads `application-local.yml`).
App starts on `localhost:8080`.

### Python ML server
```
cd ml/
py -m uvicorn serve:app --host 0.0.0.0 --port 8000 --reload
```
The `--reload` flag watches `.py` files but NOT `.pkl` model files.
**After retraining, always restart uvicorn manually.**

### Build
```
./mvnw compile           # incremental
./mvnw package -DskipTests -Dmaven.compiler.useIncrementalCompilation=false  # force full
```

---

## Project Structure

```
FantasyFootball/
├── src/main/java/com/firstember/fantasyfootball/
│   ├── domain/          # JPA entities
│   ├── repo/            # Spring Data repositories
│   ├── web/             # MVC controllers (AdminController, PredictionsController, etc.)
│   ├── sleeper/         # Sleeper API client (SleeperService, DTOs)
│   └── ml/              # ML bridge (MlPredictionService, ConsistencyStats)
├── src/main/resources/
│   ├── templates/       # Thymeleaf HTML (predictions/index.html, admin/sync.html, etc.)
│   ├── static/css/      # style.css
│   └── application.yml
├── ml/                  # Python ML pipeline
│   ├── features.py      # Feature engineering shared by train.py and serve.py
│   ├── train.py         # Training script
│   ├── serve.py         # FastAPI prediction server
│   ├── data/            # CSVs exported from admin panel (gitignored)
│   │   ├── fantasy_stats_all.csv    # season stats (input to train.py)
│   │   └── fantasy_weekly_all.csv  # weekly stats with opponent_code (schedule strength)
│   └── models/          # Trained .pkl files (gitignored)
│       └── {QB,RB,WR,TE,K,DST}_model.pkl
├── docker-compose.yml
├── pom.xml
└── CLAUDE.md            # this file
```

---

## Data Model (key entities)

### `Player`
- One row per (player, season). Same player appears multiple times across seasons.
- `external_id` = Sleeper's player ID
- `position`: QB / RB / WR / TE / K / **DST** (Sleeper sends "DEF", we normalize to "DST")
- `team`: ManyToOne → `Team` (lazy-loaded; nullable — some players have no current team)
- `season`: year (2020–2025)

### `PlayerStat`
- One row per (player, season) — aggregated season totals
- `total_points` = Sleeper's `pts_ppr` (PPR-scored fantasy points, already computed)
- `rank` = overall PPR rank within that season
- Unique constraint: `(player_id, season)`

### `PlayerWeeklyStat`
- One row per (player, season, week)
- `total_points` = that week's PPR points
- `opponent_code` = VARCHAR(10), **nullable** — the team code of the defense they faced that week
  - Populated during new syncs automatically via ESPN schedule API
  - Must be **backfilled** for existing seasons via Admin → "Sync Opponent Data"
- Unique constraint: `(player_id, season, week)`

### `PlayerPrediction`
- Stores the ML model's projected PPR points for a future season
- `predicted_season` = the season being projected (e.g. 2026)
- `projected_points` = final model output (PPR total), after all of serve.py's guardrails
- `model_points` / `market_points` / `market_adp` — market context snapshotted at each predict
  sync (all nullable). `model_points` = the projection *before* the Guardrail 5 market blend;
  `market_points` = FantasyPros consensus PPR; `market_adp` = Fantasy Football Calculator ADP.
  Persisted so downstream features (e.g. week-by-week start/sit) can read them without
  re-hitting the rate-limited external APIs. Rewritten every `/admin/predict` run (rows are
  wiped + reinserted), so they're "as of the last sync", not a time series — a dedicated
  market-snapshot table is future work if weekly refresh cadence is needed.
- Unique constraint: `(player_id, predicted_season)`

### `Team`
- NFL team code (e.g. "KC", "SF", "LV", "JAX")
- Sleeper sends "LVR" → we normalize to "LV"; "JAC" → "JAX"
- ESPN sends "WSH" → we normalize to "WAS"; "LA" → "LAR"

### `FantasyLeague` / `FantasyTeam` / `FantasyRosterPlayer`
- Sleeper fantasy leagues — **private per-user, not shared**. If two different app users are in
  the same real Sleeper league and both add it, each gets their own independent `FantasyLeague`
  row (own sync, own re-sync cadence) rather than one shared row. `FantasyLeague.owner` (→
  `User`) enforces this: `(sleeper_league_id, owner_id)` is the unique constraint, not
  `sleeper_league_id` alone, and every repository lookup in `LeagueController` is scoped by
  `owner_id` — a user can never view/sync/delete another user's league, even by guessing its id
  (`GET /league/{id}` on someone else's league just renders "not found," it doesn't leak
  existence). Add/remove/re-sync from the `/league` page itself (paste a Sleeper league id), not
  via admin or config — `/admin` is being closed off from public access.
- `FantasyTeam` = one roster slot (a team in a league): owner, custom team name (falls back to
  Sleeper display name), record, division. It stores `ownerUserId` (Sleeper's, from the sync) but
  **deliberately does not store "is this mine"** — see Authentication below for why that has to
  be resolved per-request instead (still needed even though leagues are private now — a league
  still has 11 other teams belonging to leaguemates who aren't you).
- `FantasyRosterPlayer` stores the raw Sleeper player id, not a hard FK to `Player` — `Player` is
  season-indexed and a fantasy roster is a "right now" concept with no season of its own.
  Resolved to a display name/position at read time via `PlayerRepository.findFirstByExternalIdOrderBySeasonDesc()`.
- Rosters are empty until a league leaves `pre_draft` — that's expected, not a bug. Re-sync after
  a draft to populate them. **Sleeper's standalone "mock draft" tool does NOT populate
  `league/rosters`** — it's a separate object from a league's real draft. To test the roster-sync
  path, create a small throwaway league and run an actual (even fast/instant) draft in it; a
  practice mock draft won't exercise this code path at all.

### `User`
- A real app account (email + BCrypt password hash) — separate from Sleeper identity, since
  Sleeper has no OAuth/login for third-party apps (it's a read-only public API). See
  Authentication below.

---

## Authentication & Multi-User

The app moved from "single personal tool" to "shared app, multiple people log in" — this shaped
several decisions worth knowing before touching auth or the league feature:

- **Trusted-group phase, built to extend to public later**: registration (`/register`) is
  currently open, no invite code or email verification. `User.enabled` exists specifically so a
  future public rollout is a policy change (gate `enabled` on email verification) rather than a
  data model change.
- **Real accounts, not "just pick a Sleeper username"**: since Sleeper has no login delegation,
  `User` has its own email/password, and separately holds `sleeperUsername` (set via `/profile`).
  On save, `sleeperUsername` is resolved to `sleeperUserId` immediately via
  `FantasyLeagueService.resolveSleeperUserId()` and cached — this id (not the username) is what
  actually gets compared against `FantasyTeam.ownerUserId`, since Sleeper display names aren't
  guaranteed stable/unique the way the numeric id is.
- **Leagues are private per-user (own row per owner), but "mine" is still per-viewer within a
  league**: your own league still has 11 other teams belonging to leaguemates, so "is this my
  team" still can't be a stored fact on `FantasyTeam`. `LeagueController.detail()` computes a
  `mineMap` per request by comparing the *current logged-in user's* cached `sleeperUserId`
  against each team's `ownerUserId`. Do not reintroduce a stored `isMine`/`mine` field on
  `FantasyTeam` — it was removed for exactly this reason.
- **Stale-session gotcha**: Spring Security caches the principal object (`AppUserPrincipal`,
  wrapping `User`) in the HTTP session at login time. If a profile edit (e.g. linking a Sleeper
  account) only updates the DB, the *current* session keeps serving the old cached values until
  the user logs out and back in — the "YOU" tag silently wouldn't appear even though the DB is
  correct. `ProfileController.refreshSessionPrincipal()` fixes this by rebuilding the session's
  `Authentication` immediately after every save. Any future controller that mutates `User` needs
  to call something equivalent, or the change won't be visible until next login.
- **CSRF is enabled** (`thymeleaf-extras-springsecurity6` is already a dependency, which
  auto-injects the `_csrf` hidden field into every `th:action` form — no per-form changes
  needed). It was disabled in the original scaffold; re-enabling it required no other changes
  since every existing POST form in the app already uses `th:action`.
- **Whole app requires login** except `/login`, `/register`, and static assets — this was a
  deliberate broad choice (not just gating the league feature) since the app is moving toward
  being a real multi-user product.
- **`/admin/**` requires `ROLE_ADMIN`**, not just login. `User.role` (enum `USER`/`ADMIN`,
  default `USER`) drives this via `AppUserPrincipal.getAuthorities()` — an admin gets both
  `ROLE_USER` and `ROLE_ADMIN` (additive, not a replacement). There is no self-service promotion
  path or admin UI for granting the role; it's set directly in the DB
  (`UPDATE app_users SET role='ADMIN' WHERE email='...'`). Trusted-group members should not get
  Sleeper-sync/ML-predict/CSV-export access just by having an account.

---

## Admin Panel (`/admin/sync`)

All data management happens here. Workflow order matters:

### 1. Sleeper Sync (`POST /admin/sync?year=XXXX`)
- Fetches all 18 regular season weeks from Sleeper API
- Fetches ESPN schedule for that year (to populate `opponent_code`)
- Upserts Players, PlayerStats (season totals), PlayerWeeklyStats (per week)
- Recalculates PPR rank for all players in that season
- Takes 1–3 minutes per season (18 Sleeper API calls + 18 ESPN calls)

### 2. Sync Opponent Data (`POST /admin/sync-opponents?year=XXXX`)
- Backfills `opponent_code` on existing `PlayerWeeklyStat` rows for seasons synced before
  the opponent feature was added
- Calls ESPN's public scoreboard API per week
- Run for each synced season: 2020, 2021, 2022, 2023, 2024, 2025

### 3. Export Season Stats → `ml/data/fantasy_stats_all.csv`
- `GET /admin/export` (all seasons) or `GET /admin/export?year=XXXX`
- Includes computed columns: `games_played`, `reception_pct`, consistency stats

### 4. Export Weekly Stats → `ml/data/fantasy_weekly_all.csv`
- `GET /admin/export/weekly`
- Columns: `season, week, full_name, position, team_code, opponent_code, total_points`
- Required for the `opp_pts_allowed` (schedule strength) feature in the ML model

### 5. Train the model
```
cd ml/
py train.py
```

### 6. Restart the ML server
```
# Ctrl+C the running uvicorn, then:
py -m uvicorn serve:app --host 0.0.0.0 --port 8000 --reload
```

### 7. Sync Predictions (`POST /admin/predict`)
- `sourceSeason` (default 2025): which season's stats to use as input
- `targetSeason` (default 2026): which season to project for
- Sends all player stats to the Python API → stores projections in `PlayerPrediction`
- Requires Python server running with trained models loaded

### 8. Fantasy League (`/league`, not `/admin`)
- Deliberately lives outside the admin panel, which is being closed off from public access —
  adding/syncing/deleting leagues needs to stay usable without admin access
- `GET /league` — list every added league
- `POST /league/add` (`sleeperLeagueId` form param) — add and immediately sync a league
- `POST /league/{id}/sync` — re-sync one league's teams/owners/records/rosters
- `POST /league/{id}/delete` — remove a league and everything under it
- `GET /league/{id}` — teams/rosters detail view
- Which team gets flagged "YOU" is per-viewer, from the logged-in user's linked Sleeper account
  (`/profile`) — see the Authentication section above, not a config value

### 9. Live Draft (`/draft/{sleeperDraftId}`)
- Works against ANY Sleeper draft id, not just ones tied to a synced league — a standalone
  practice "mock draft" has its own `draft_id` with no `league_id`, and `DraftController`/
  `DraftService` don't require a league match to show the live board. This is the intended way
  to test the feature before a real draft (see the roster-sync gotcha above about why a mock
  draft doesn't test *everything*).
- `GET /draft/{draftId}` — the board page; `GET /draft/{draftId}/data` — the JSON the frontend
  polls every 4s (Sleeper has no push/websocket feed for third-party apps). Reused for both a
  linked league (real team names, persists new picks into `FantasyRosterPlayer` — idempotent,
  safe to re-poll) and a standalone draft (generic "Team {rosterId}" labels, nothing persisted
  since there's no real league to attach picks to).
- Suggestions are ranked by a composite score: our own stored `PlayerPrediction` for
  `TARGET_SEASON` (already ADP-aware via Guardrail 5) × `NEED_CURVE` (roster fill) ×
  `POSITION_SCARCITY_WEIGHT`. The scarcity weights are the "when does this position get drafted"
  lever: RB 1.0 (baseline), WR 0.92, QB 0.65, **K/DST 0.30** (the need curve only ever
  de-prioritised a *second* K/DST — 0.30 keeps the *first* one out of the suggestions until the
  final rounds, where the need curve's 1.2 factor floats it back to the top of what's left).
- **ADP-only rows**: `buildSuggestions` also emits rows for players the market is drafting that
  we have **no `PlayerPrediction` for** — mainly the current rookie class (no prior NFL season
  to project, no synced `TARGET_SEASON` stats). Source is `FantasyCalculatorService.currentAdpEntries`
  (full ADP rows, same cache as `currentAdp`). `projectedPoints` is null; they're ranked by a
  conservative pseudo-score `max(20, 200 - adp) × need × scarcity` that interleaves them among
  real projections at roughly their ADP-implied value (capped so an unproven name never
  out-ranks a comparable player we have real numbers on), and flagged `adpOnly:true` → the board
  shows an `ADP` tag and "—" for projection. Filtered out once drafted by **name-key**
  (`draftedNameKeys`), since these rows carry no Sleeper `player_id`. Established players are
  de-duped against the projection list by `NameUtil.key`.
- Drafted players with a projection are filtered out by Sleeper `player_id` (`draftedPlayerIds`).
- **Injury badges**: `/draft/{id}/data` attaches a live `injuryStatus` / `injuryBodyPart` to
  every suggestion, top-3 pick, and My-Team row, from `SleeperService.currentInjuryStatuses()`
  (Sleeper `injury_status`, keyed by `externalId`, cached 5 min so the 4s poll is cheap). The
  board renders an amber `Q` for Questionable and a red badge (`OUT`/`D`/`IR`/`PUP`/…) for
  anything more serious, full text + body part in the `title`. Snapshot only — not persisted on
  `Player`, not a model input; purely a draft-time display signal. NOT shown in the round grid
  cells (too cramped) by design.
- Entry points: a "Live Draft" button on `/league/{id}` (only shown if `sleeperDraftId` is set),
  and a standalone "Track a Draft" form on `/league` for pasting any draft id, including a mock
  draft's.
- Board is a Sleeper-style round × team grid (rows = rounds, columns = `draft_slot` 1..N), not a
  flat pick list. **Columns are physical draft positions, not fixed team identities** — some
  leagues (this app's own test league included) snake for the first 2 rounds then re-scramble
  the pick order, so whoever sits in column 3 for round 1 is NOT necessarily who sits there in
  round 4. Two consequences that must not get "simplified" away:
  - The header row (`slotHeaders`) is only a best-effort "who's here right now" guess (seeded
    from `slot_to_roster_id`/`draft_order`, then overwritten as picks reveal the current mapping).
    It is explicitly NOT trusted for attributing any individual pick.
  - Every pick's *own* team is always resolved fresh from that pick's own data — `roster_id` for
    a linked league (a real team's roster_id is stable all draft long, unlike its column), or
    `picked_by` (Sleeper user id) for a standalone draft — never from column/slot position. Each
    drafted cell shows this as a small corner label, same as Sleeper's own board does, and for
    the same reason: it's the only way to stay correct across a re-scramble. Don't go back to
    "group all of a team's picks under one fixed column" — that was tried first and is wrong for
    exactly this scenario (all 17 picks landed under one bucket in one bad version).
  - "On the clock" — the *slot* due next is still deterministic from standard snake traversal
    (position order doesn't change even when team-per-slot does), computed locally from
    `pickCount + snake math`, not trusted from Sleeper directly. The *label* shown for it is the
    same best-effort guess as the header.
  - **Confirmed against a real league's board that this can be a genuinely unpredictable,
    repeating reshuffle** — not a one-time thing, and not Sleeper's documented `reversal_round`
    setting (checked the raw draft JSON for this app's own test league: `reversal_round` was `0`
    /disabled, yet the live board still showed a different team-per-column mapping every 2
    rounds). Comparing column contents round-by-round on a real Sleeper board: rounds 5&6 had an
    identical mapping, 7&8 had an identical mapping, but 4→5, 6→7, and 8→9 each changed. Whatever
    generates this isn't exposed anywhere in `/draft/{id}`'s settings — there is no way to
    predict a future, not-yet-reached round's mapping *from the API*. Every undrafted grid cell is
    labeled as a guess (`.cell-team.guess`, dimmed) — don't "fix" that by making future cells look
    confirmed.
  - **Manual override for one specific draft** (`CUSTOM_ORDER_*` constants + `customRoundOrder()`
    in `DraftController`): when the real per-block order was transcribed by hand from the live
    Sleeper board, the override supplies it for that one `draftId`. It fixes the forward-looking
    bits pure-snake gets wrong from round 3 on: who's on the clock + the "YOUR PICK" flag, the
    viewer's `upcomingMyPicks` list (pure pick-count arithmetic — safe regardless of how Sleeper
    numbers `draft_slot`), and `plannedOrder` (undrafted grid-cell labels, still rendered dimmed
    as "planned order"). Values are round-1 seat numbers; only odd rounds stored, even rounds are
    their reverse; rounds past the data fall back to snake. It is **display only** — a made pick's
    team still comes from its own `roster_id`, never this table. Delete the block after the draft.
- The suggestions query MUST use `PlayerPredictionRepository.findByPredictedSeasonWithPlayer()`
  (a JOIN FETCH), not the plain `findByPredictedSeason()` — the plain version lazy-loads
  `player`/`player.team` per row, meaning ~630 individual queries on every single poll (every
  4s). Caused real, user-visible lag before this was caught.

---

## ML Pipeline

### Training target
The model predicts **PPR total points for the next season**.
PPR scoring weights are already baked into the historical `pts_ppr` totals from Sleeper —
we do NOT manually apply scoring weights. The model learns patterns from the PPR totals directly.

### Games-played handling (training pairs — `train.py`)
- **Feature season (N)**: `MIN_GAMES` (default **10**) drops pairs whose season-N row is too thin
  to build a stable per-game rate from. Applied **after** the lag join, so it only gates the
  feature side. Tune via the `MIN_GAMES` env var.
- **Target season (N+1)**: **never** filtered on games. Excluding injury/bench-shortened
  outcomes would chop the bottom off the outcome distribution and bias every projection upward
  (across *healthy* follow-up seasons elite RBs regress ~13%; the ~29% figure everyone quotes is
  injury years dragging the mean). Instead each pair is weighted `min(target_games, 17) / 17`
  (`train.py._sample_weights`) — a full target season counts 1.0, an 8-game one ~0.47 — so real
  decline stays in the fit but unforeseeable missed time doesn't dominate it. `model.fit` and the
  walk-forward `evaluate()` folds both pass `sample_weight`; the `cross_val_score` line in
  `train()` is left unweighted (rough console check only). Raising `MIN_GAMES` and adding weights
  trades ~3–7% of raw unweighted walk-forward MAE for less top-tier lowballing — an expected
  trade, since the unweighted test still scores the model on the seasons it was told to
  de-prioritize. `target_games_played` rides along from `make_training_pairs`; `build_X` ignores
  it so it never becomes a feature.

### One model per position
`QB`, `RB`, `WR`, `TE`, `K`, `DST` — each trained on separate data.
Algorithm: `GradientBoostingRegressor(n_estimators=200, max_depth=3, learning_rate=0.05, subsample=0.8)`

### Feature groups (see `ml/features.py` → `POSITION_FEATURES`)
| Feature | What it captures |
|---|---|
| `total_points`, `points_per_game` | Overall productivity |
| Position-specific stats | Passing/rushing/receiving/kicking/defense stats |
| `age`, `age_sq` | Age curve (quadratic) |
| `games_played` | Availability / durability |
| `prev2_total_points`, `prev2_points_per_game` | Season N-1 stats (trend context) |
| `pts_delta` | Improvement/decline from N-1 (zero for rookies) |
| `has_prev2` | 0 if first-year player, 1 if N-2 data exists |
| `weekly_pts_std` | Boom/bust indicator (std dev of weekly scores) |
| `floor` | Mean of bottom 4 weekly scores |
| `ceiling` | Mean of top 4 weekly scores |
| `games_over_10`, `games_over_20` | Consistency thresholds |
| `opp_pts_allowed` | Avg pts opponents allowed to this position per game (schedule strength) |
| `snap_pct` | % of team's offensive snaps played (season avg); QB/RB/WR/TE only, not K/DST |

### Key guardrails in `serve.py`
1. **Rookie blending**: First-year players (`has_prev2=0`) get 55% model prediction + 45% position mean (prevents runaway extrapolation from a single season)
2. **Swing cap**: Projection clamped to 0.60×–1.40× prior season total, symmetric (prevents extreme outliers in either direction — the model has no roster-context features, so a large predicted drop is just as likely to be regression-to-mean noise as a large predicted jump)
3. **Status discount**: Players whose *current* Sleeper roster status is Injured Reserve/PUP/Suspended/Inactive get a modest projection haircut (`STATUS_DISCOUNTS` in serve.py). `status` is never a trained feature — see the gotcha below for why.
4. **Live depth-chart nudge**: `depth_chart_order` (1 = current starter) is pulled fresh from Sleeper at prediction time via `SleeperService.currentDepthChartOrders()` — RB/WR/TE at depth 1 get a modest boost, depth 3+ a modest discount. Same reasoning as `status`: Sleeper only ever reports *today's* depth chart, so this can never be a trained feature, but it's the only forward-looking signal available for "did a teammate who competed for touches leave this offseason" — every trained feature is backward-looking (last season's box scores). Deliberately untuned/heuristic, and applied after the swing cap so it can push a projection beyond +/-40% when there's a specific reason to.
5. **Market consensus blend**: pulls the projection toward FantasyPros' consensus PPR projection (`market_points`). The pull weight is `base_weight * adp_factor`, capped at **0.85**: `base_weight = min(0.50, 1.25*|gap|)` grows with the gap; `adp_factor = max(0, 0.75 + 1.6*(adp_trust - 0.5))` scales it by how strongly the player's live ADP (Fantasy Football Calculator, free/keyless) corroborates that gap — **0.75× at the no-ADP midpoint (the pre-Fix-A behaviour), ~1.53× when ADP is top-of-position (so two agreeing forward-looking sources CAN push the blend past the 0.50 base), 0 when ADP flatly contradicts.** This is a stronger, more general version of Guardrail 4 — added after diagnosing that elite-tier players (Gibbs, Bijan, Bowers, Nacua) were underrated by 25-36% because their value story is entirely situational/offseason-driven, something no trained feature can see. The `adp_factor` widening ("Fix A") was added later when the original `0.5 + 0.5*adp_trust` (hard 0.50 cap) still left Gibbs/Bijan ~RB4 despite being ADP #1/#2; with it they project RB1/RB2. serve.py also returns `model_points` (the projection *before* this blend) so the app can show "model → market". See `FantasyCalculatorService` and `FantasyProsService` in `com.firstember.fantasyfootball.external`.

### Evaluation
`py train.py eval` — runs walk-forward cross-validation (trains on past seasons, tests on future).
This is the honest accuracy estimate; do NOT use random k-fold (it leaks future data).

Walk-forward MAE results (2020–2025 data, `MIN_GAMES=10` + target-availability sample weights;
test MAE is unweighted so it still counts injury-shortened seasons the fit de-prioritizes):
- QB: ~85.1 pts | RB: ~60.5 | WR: ~51.6 | TE: ~41.9 | K: ~30.8 | DST: ~28.5
- Overall average: ~49.7 pts

Prior run (`MIN_GAMES=4`, no sample weights) was ~47.6 overall / RB ~53.9 — the rise is mostly
test-set composition (fewer easy low-volume players) plus the model no longer optimizing for
injury-year targets. Judge changes off the RB line and the elite-tier projections, not the
aggregate.

---

## Key Design Decisions

**Why one player row per season instead of one row per player?**
The same player (e.g. Josh Allen) exists as multiple `Player` rows — one for each season.
This was a deliberate design choice to keep season stats isolated. Cross-season joins happen
in the ML layer by matching on `(full_name, position)`.

**Why `pts_ppr` from Sleeper as total_points?**
Sleeper computes the PPR score server-side. We store it directly rather than recomputing
from raw stats. This means the model target is already the correct PPR-scored number.

**Why `player_weekly_stats` for games_played instead of storing it in PlayerStat?**
`PlayerStat` stores season totals but not game count. Games played is computed from
`COUNT(*) FROM player_weekly_stats WHERE player_id=X AND season=Y`.

**Why ESPN schedule API for opponent_code?**
Sleeper's stats endpoint does not include the opponent. ESPN's free, keyless schedule feed
(`cdn.espn.com/core/nfl/schedule?xhr=1&year=&week=&seasontype=2`) returns home/away matchups
per week. NOTE: use the `cdn.espn.com/core` feed, not `site.api.espn.com/apis/.../scoreboard`
— the latter is Akamai-Bot-Manager-gated and 403s the JVM (see Known Gotchas).

**Why SOURCE_SEASON=2025, TARGET_SEASON=2026 in PredictionsController?**
The app projects the upcoming season (2026) based on the most recently completed season (2025).
Update these constants when a new season starts.

---

## Known Gotchas & Past Bugs

- **`py` not `python`**: Python is on PATH as `py` on this machine. Never use `python` or `pip` directly. Use `py train.py`, `py -m uvicorn`, `py -m pip install`.
- **Port 5433 not 5432**: The Docker PostgreSQL container maps to 5433.
- **Thymeleaf ternary expressions**: Must wrap the entire expression in a single `${}`. Splitting across `th:style` and `th:text` with external ternary causes parse errors.
- **`pts_delta` for rookies**: First-year players must have `pts_delta = 0` (not `total_points - 0`). Multiplying by `has_prev2` achieves this in both Python features.py and Java MlPredictionService.
- **DST position naming**: Sleeper sends "DEF", our DB stores "DST". SleeperService normalizes this in `upsertPlayer()`.
- **Team code mismatches**: Sleeper uses "LVR"/"JAC", ESPN uses "WSH"/"LA". Normalization maps are `TEAM_CODE_FIX` (Sleeper) and `ESPN_CODE_FIX` (ESPN) in SleeperService.
- **ConsistencyStats**: Must be a standalone file at `ml/ConsistencyStats.java` — NOT a static inner class. Spring's class loader cannot find inner classes across packages.
- **`games_played` in ML payload**: Comes from counting `PlayerWeeklyStat` rows, not stored in `PlayerStat`. If `countWeeksByPlayerForSeason` returns 0, points_per_game defaults to total_points (a bug symptom, not a bug itself).
- **Maven incremental compile**: After editing Java files on Windows, run with `-Dmaven.compiler.useIncrementalCompilation=false` if changes aren't picked up. Or `touch` the source files. This can silently mask a real compile error: `./mvnw compile -q` reported clean success after a change that actually introduced a duplicate-variable error (a method parameter and a loop-local variable both named `owner` in `FantasyLeagueService.syncLeague`) — the stale pre-edit class was reused instead of recompiling. The error only surfaced at runtime once `spring-boot:run`'s devtools recompiled it for real, as `java.lang.Error: Unresolved compilation problem`. If a change compiles suspiciously cleanly, re-run with `-Dmaven.compiler.useIncrementalCompilation=false` to be sure.
- **`ddl-auto: update` never drops or alters existing constraints, only adds new ones**: when `FantasyLeague.sleeperLeagueId` changed from a single-column `unique = true` to a compound `(sleeper_league_id, owner_id)` constraint (the private-per-user-leagues migration), Hibernate happily added the new compound constraint but left the old single-column one in place — nothing in the entity said to remove it. Silently broke the "two different users add the same real league" case with a `DataIntegrityViolationException` until the stale constraint was dropped manually (`\d fantasy_leagues` in psql to spot old constraints, then `ALTER TABLE ... DROP CONSTRAINT`). Check for this any time a uniqueness rule changes shape, not just when a column is added.
- **Unicode in Python on Windows**: Use ASCII characters in print statements in train.py. `→` (U+2192) causes `UnicodeEncodeError` on Windows cmd with cp1252 encoding.
- **Sleeper's `/players/nfl` is a live snapshot, not history**: `age`, `status`, `depth_chart_order`, `injury_status`/`injury_body_part` all come from this endpoint, which only ever returns *today's* values — there is no season-specific version of any of them. `SleeperService.upsertPlayer()` derives `age` from the stored `birth_date` as-of Sept 1 of the season being synced (`ageForSeason()`), so re-syncing an old season no longer stamps today's age onto that historical row. `status` / `depth_chart_order` / `injury_status` have no historical equivalent at all, so none is a trained feature: `status` is a prediction-time guardrail (serve.py `STATUS_DISCOUNTS`), `depth_chart_order` a prediction-time nudge (Guardrail 4), and `injury_status` is fetched fresh only for the live draft board's badges (`SleeperService.currentInjuryStatuses()`, not persisted anywhere). Rows synced before the `age` fix have `birth_date = NULL` and a stale `age` until re-synced.
- **Don't log1p-transform the training target to fix elite-tier compression — tried it, made it worse**: Only ~2.5% of historical RB-seasons ever top 300 points, so the model badly under-projects breakout players (e.g. projected Jahmyr Gibbs/Bijan Robinson far below external consensus for 2026, despite both having a clear path to more touches). Wrapping the model in `TransformedTargetRegressor(func=np.log1p, inverse_func=np.expm1)` seemed like a fix, but `log1p` *shrinks* the loss's sensitivity to absolute gaps at the high end (log(400)-log(300) is small) while *inflating* it at the low end — it made the model care less about the tail, not more. Verified empirically: raw (pre-guardrail) output for Christian McCaffrey dropped to ~100 projected points. Reverted in `train.py`. The real fix needs a genuine roster-context feature (target share, backfield competition) — this model has no signal at all for "his backup left," which is the actual reason sites like Razzball project these players 35-40% higher than we do. See Future Work item 3.
- **Elite-RB compression fix (Gibbs/Bijan) took two changes together, not one**: (1) target-availability sample weights + `MIN_GAMES=10` on the *feature* season (see ML Pipeline → "Games-played handling") lifted raw RB model output for Gibbs ~195 → ~228 but barely moved Bijan (~253 → ~257); alone, the guardrail stack still landed both ~296 (RB4-ish). (2) "Fix A" — widening Guardrail 5's `adp_factor` and raising its cap 0.50 → 0.85 (see guardrail list) — then carries them to Gibbs ~331 / Bijan ~309, i.e. RB1/RB2 on the projected board, vs FantasyPros ~353-370 and ADP #1/#2. Neither change alone was enough: the weighting doesn't touch inference, and the old blend couldn't pull a still-30%-low number far enough. Longer term a real roster-context feature (target share, backfield competition) or ADP-as-a-trained-feature would let the model get there without leaning so hard on the blend. See Future Work item 3.
- **Legacy `Data_2024` CSV import left 1,079 orphaned duplicate rows**: predates the Sleeper sync pipeline (see the `./fantasy-football/Data_2024:/import` mount in docker-compose.yml). These had `players.season = NULL`, zero weekly stats, but a nonzero `total_points` — internally contradictory. `train.py`'s `MIN_GAMES` filter incidentally protected training data from them, but `MlPredictionService`'s `prev2StatMap` (keyed by `fullName|position`, no games-played guard) silently preferred whichever row loaded last, corrupting `prev2_points_per_game` to 0 for ~559 players. Cleaned up via one-time DB delete (`DELETE FROM player_stats/players WHERE season IS NULL` after confirming zero weekly-stat and zero-prediction references). If this pattern reappears after a future bulk import, check for `players.season IS NULL` first.
- **FantasyCalculatorService silently returned empty ADP for a while**: Fantasy Football Calculator's Cloudflare-cached responses are sometimes mislabeled `Content-Type: text/html` even though the body is valid JSON. curl and Python's `urllib` don't care and parse it fine, but Spring's `RestTemplate.getForObject(url, SomeClass.class)` strictly validates Content-Type before picking an `HttpMessageConverter` and throws (caught, logged as a WARN, degrades to the cached/empty map) rather than deserializing. Fixed by fetching as `String` and parsing with a local `ObjectMapper` instead, bypassing content-type negotiation entirely. Worth knowing: this means `adp_trust` in Guardrail 5 was silently defaulting to a neutral 0.5 for every player for a while (the `fillna(0.5)` in serve.py never had real ADP to fill from) — after this fix, re-run "Sync AI Predictions" to get the real, differentiated trust signal instead of the flat default.
- **Draft-board bye weeks (and opponent codes) stayed blank — `site.api.espn.com` 403s the JVM**: ESPN's `site.api.espn.com/.../scoreboard` endpoint is fronted by **Akamai Bot Manager**, which returns **HTTP 403 to Java's HTTP client no matter what headers we send** — it fingerprints the client's TLS handshake, so a browser `User-Agent` doesn't help (verified: `curl` → 200, `curl -A "Java/17"` → 403, and a full Chrome UA from `HttpURLConnection` → still 403). `curl`/`urllib` work, which is why it looked fine from the shell. Fix: `SleeperService.fetchEspnScheduleForWeek` now hits **`cdn.espn.com/core/nfl/schedule?xhr=1&year=&week=&seasontype=2`** instead — same matchups in one call, `application/json`, not bot-gated (works from Java with any/no UA). Response shape is `content.schedule.{YYYYMMDD}.games[].competitions[0].competitors[].team.abbreviation`. This also un-breaks opponent-code sync/backfill, which uses the same method. Separately hardened `byeWeeksByTeam`: single-flight (`ReentrantLock.tryLock`, non-blocking for concurrent 4s polls), cache-the-result-either-way with a 10-min cooldown before retrying an incomplete one, failed weeks don't count as a bye, and a corruption guard (>8 teams sharing a bye week ⇒ discard).

---

## API Endpoints Reference

### Web (Spring Boot, port 8080)
| Method | Path | Purpose |
|---|---|---|
| GET | `/` | Home page |
| GET | `/players` | Player list with PPR stats |
| GET | `/players/{id}` | Player detail |
| GET | `/teams` | Team list |
| GET | `/stats` | Season stats table |
| GET | `/predictions` | AI predictions page |
| GET | `/admin/sync` | Admin panel |
| POST | `/admin/sync?year=` | Trigger Sleeper sync |
| POST | `/admin/sync-opponents?year=` | Backfill opponent_code via ESPN |
| POST | `/admin/predict?sourceSeason=&targetSeason=` | Run ML prediction sync |
| GET | `/admin/export` | Download season stats CSV |
| GET | `/admin/export?year=` | Download single-season CSV |
| GET | `/admin/export/weekly` | Download weekly stats CSV |
| GET | `/league` | List your added Sleeper leagues |
| POST | `/league/add` | Add + sync a league (`sleeperLeagueId` param) |
| GET | `/league/{id}` | One league's teams/owners/rosters |
| POST | `/league/{id}/sync` | Re-sync one league |
| POST | `/league/{id}/delete` | Delete a league |
| GET/POST | `/register` | Create an account |
| GET | `/login` | Log in (Spring Security form login) |
| POST | `/logout` | Log out |
| GET/POST | `/profile` | Link/change your Sleeper username |
| GET | `/draft/{draftId}` | Live draft board (any Sleeper draft id) |
| GET | `/draft/{draftId}/data` | JSON polling endpoint for the board |

### ML Server (Python FastAPI, port 8000)
| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Check loaded models |
| POST | `/predict/season` | Season projection for all players |
| POST | `/predict/week` | Week-by-week (stub, not implemented) |

---

## Future Work / Potential Improvements

These were discussed but not yet implemented:

1. Week-by-week predictions (`POST /predict/week` stub exists in serve.py)
2. Injury/status signals — Sleeper returns `status` field on players
3. Team offense context — target share, backfield splits
4. ADP (Average Draft Position) integration
5. Draft recommendations page
6. User-facing league scoring customization (currently hardcoded PPR)
