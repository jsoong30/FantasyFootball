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
- `projected_points` = model output (PPR total)
- Unique constraint: `(player_id, predicted_season)`

### `Team`
- NFL team code (e.g. "KC", "SF", "LV", "JAX")
- Sleeper sends "LVR" → we normalize to "LV"; "JAC" → "JAX"
- ESPN sends "WSH" → we normalize to "WAS"; "LA" → "LAR"

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

---

## ML Pipeline

### Training target
The model predicts **PPR total points for the next season**.
PPR scoring weights are already baked into the historical `pts_ppr` totals from Sleeper —
we do NOT manually apply scoring weights. The model learns patterns from the PPR totals directly.

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
2. **Improvement cap**: Max projection = 1.40× prior season total (prevents extreme outliers)
3. **Status discount**: Players whose *current* Sleeper roster status is Injured Reserve/PUP/Suspended/Inactive get a modest projection haircut (`STATUS_DISCOUNTS` in serve.py). `status` is never a trained feature — see the gotcha below for why.

### Evaluation
`py train.py eval` — runs walk-forward cross-validation (trains on past seasons, tests on future).
This is the honest accuracy estimate; do NOT use random k-fold (it leaks future data).

Walk-forward MAE results (as of last evaluation with 2020–2025 data, incl. snap_pct feature):
- QB: ~84.5 pts | RB: ~56.8 | WR: ~47.8 | TE: ~35.5 | K: ~32.5 | DST: ~28.5
- Overall average: ~47.6 pts

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
Sleeper's stats endpoint does not include the opponent. ESPN's public scoreboard API
(`site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard`) is free, no key required,
and returns home/away matchups per week.

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
- **Maven incremental compile**: After editing Java files on Windows, run with `-Dmaven.compiler.useIncrementalCompilation=false` if changes aren't picked up. Or `touch` the source files.
- **Unicode in Python on Windows**: Use ASCII characters in print statements in train.py. `→` (U+2192) causes `UnicodeEncodeError` on Windows cmd with cp1252 encoding.
- **Sleeper's `/players/nfl` is a live snapshot, not history**: `age` and `status` both come from this endpoint, which only ever returns *today's* values — there is no season-specific age/status in Sleeper's data. `SleeperService.upsertPlayer()` now derives `age` from the player's stored `birth_date` as-of Sept 1 of the season being synced (`ageForSeason()`), so re-syncing an old season no longer stamps today's age onto that historical row. `status` has no historical equivalent at all, so it is intentionally **not** a trained feature — it's only used as a prediction-time guardrail (discount for currently-injured/inactive players). Existing rows synced before this fix have `birth_date = NULL` and a stale `age` until re-synced.

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
