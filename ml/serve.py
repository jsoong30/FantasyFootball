"""
FastAPI prediction server consumed by the Spring Boot app.

Endpoints:
  GET  /health                → model status
  POST /predict/season        → season-total projections (current)
  POST /predict/week          → week-by-week projections (not yet implemented)

Start:
  uvicorn serve:app --host 0.0.0.0 --port 8000 --reload

The Java app calls POST /predict/season with all current-season player stats
and receives projected fantasy points for next season.
"""

import os
import joblib
import pandas as pd
from fastapi import FastAPI
from pydantic import BaseModel
from typing import Optional

from features import POSITIONS, add_computed_columns, build_X

MODEL_DIR = os.environ.get("MODEL_DIR", "models")

# Guardrail 3 (see predict_season): discount applied to a player's projection based on
# their *current* roster status. Not a training feature — just a soft prediction-time
# haircut for players who are presently hurt or off a roster. Modest, not zero, since a
# status snapshot taken today doesn't guarantee it still applies once next season starts.
STATUS_DISCOUNTS = {
    "Injured Reserve": 0.80,
    "PUP": 0.85,
    "Suspended": 0.75,
    "Inactive": 0.90,
}

app = FastAPI(title="FantasyFootball ML API", version="1.0.0")

_models: dict = {}  # position → {"model": ..., "features": [...]}


def _load_models() -> None:
    for pos in POSITIONS:
        path = os.path.join(MODEL_DIR, f"{pos}_model.pkl")
        if os.path.exists(path):
            _models[pos] = joblib.load(path)
            print(f"  Loaded model: {pos}")
        else:
            print(f"  No model file for {pos} — run train.py first")


@app.on_event("startup")
def startup() -> None:
    print("Loading models …")
    _load_models()
    print(f"Ready. Models loaded: {list(_models.keys())}")


# ── Schemas ───────────────────────────────────────────────────────────────────

class PlayerStats(BaseModel):
    """One player's current-season stats. All stat fields default to 0."""
    name: str
    position: str
    age: Optional[int] = None
    # Sleeper's *current* roster status (e.g. "Active", "Inactive", "Injured Reserve").
    # Never trained on — Sleeper only exposes today's status, not season history — but
    # used at prediction time to discount players who are currently hurt/unrostered.
    status: Optional[str] = None
    games_played: int = 0
    total_points: float = 0.0
    # Passing
    passing_yds: int = 0
    passing_td: int = 0
    passing_int: int = 0
    # Rushing
    rushing_yds: int = 0
    rushing_td: int = 0
    # Receiving
    targets: int = 0
    receiving_rec: int = 0
    receiving_yds: int = 0
    receiving_td: int = 0
    reception_pct: float = 0.0
    fumbles: int = 0
    # Usage — % of the team's offensive snaps this player played (season average)
    snap_pct: float = 0.0
    # Kicker
    pat_made: int = 0
    pat_missed: int = 0
    fg_made: int = 0
    # DST
    def_sacks: int = 0
    def_ints: int = 0
    def_fum_rec: int = 0
    def_td: int = 0
    def_safeties: int = 0
    def_blocked_kicks: int = 0
    pts_allowed: int = 0
    yds_allowed: int = 0
    # Two-season trend features (season N-2, computed by Java before sending)
    prev2_total_points: float = 0.0
    prev2_games_played: int = 0
    prev2_points_per_game: float = 0.0
    pts_delta: float = 0.0       # total_points - prev2_total_points
    has_prev2: int = 0           # 1 if N-2 data exists, 0 otherwise
    # Weekly consistency features
    weekly_pts_std: float = 0.0  # std dev of weekly scores (boom/bust indicator)
    floor: float = 0.0           # mean of bottom 4 weeks
    ceiling: float = 0.0         # mean of top 4 weeks
    games_over_10: int = 0       # weeks scoring >10 pts
    games_over_20: int = 0       # weeks scoring >20 pts
    # Opponent-adjusted schedule strength
    opp_pts_allowed: float = 0.0  # avg pts opponents allowed to this position per game
                                   # higher = easier schedule; 0 = not yet backfilled


class SeasonPredictionRequest(BaseModel):
    players: list[PlayerStats]


class PlayerPrediction(BaseModel):
    name: str
    position: str
    projected_points: Optional[float] = None
    error: Optional[str] = None


class SeasonPredictionResponse(BaseModel):
    predictions: list[PlayerPrediction]


# ── Endpoints ─────────────────────────────────────────────────────────────────

@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "loaded_models": list(_models.keys()),
        "missing_models": [p for p in POSITIONS if p not in _models],
    }


@app.post("/predict/season", response_model=SeasonPredictionResponse)
def predict_season(request: SeasonPredictionRequest) -> SeasonPredictionResponse:
    """
    Given each player's stats from the most recent completed season,
    return projected fantasy points for the upcoming season.
    """
    # Pre-compute position means from veterans (has_prev2=1) for rookie blending.
    # Uses prior-season total_points as the baseline, not raw model outputs.
    from collections import defaultdict
    pos_pts: dict = defaultdict(list)
    for p in request.players:
        if p.has_prev2 == 1 and p.total_points > 0:
            pos_pts[p.position].append(p.total_points)
    pos_mean = {pos: sum(v) / len(v) for pos, v in pos_pts.items()}

    predictions = []

    for player in request.players:
        artifact = _models.get(player.position)
        if artifact is None:
            predictions.append(PlayerPrediction(
                name=player.name,
                position=player.position,
                error=f"No model trained for {player.position}",
            ))
            continue

        # Build a one-row DataFrame matching the training schema
        row = player.model_dump()
        df = pd.DataFrame([row])
        df = add_computed_columns(df)
        X = build_X(df, player.position)

        # Reorder columns to exactly match training order
        X = X[artifact["features"]]

        raw = float(artifact["model"].predict(X)[0])
        projected = max(raw, 0.0)

        # ── Guardrail 1: rookie blending ──────────────────────────────────────
        # First-year players (has_prev2=0) have no trend context, so the model
        # over-extrapolates from a single season. Blend 55/45 toward position mean.
        if player.has_prev2 == 0:
            mean = pos_mean.get(player.position, projected)
            projected = 0.55 * projected + 0.45 * mean

        # ── Guardrail 2: per-player improvement cap ───────────────────────────
        # Cap projection at 40% above prior season total.
        # Allows genuine breakout years while preventing runaway extrapolation.
        if player.total_points > 0:
            projected = min(projected, player.total_points * 1.40)

        # ── Guardrail 3: current status discount ──────────────────────────────
        # Players currently marked hurt/unrostered get a modest haircut. This is
        # deliberately soft — status is a snapshot taken today, not a guarantee
        # about next season.
        discount = STATUS_DISCOUNTS.get(player.status)
        if discount is not None:
            projected *= discount

        predictions.append(PlayerPrediction(
            name=player.name,
            position=player.position,
            projected_points=round(projected, 2),
        ))

    return SeasonPredictionResponse(predictions=predictions)


@app.post("/predict/week")
def predict_week() -> dict:
    """Week-by-week prediction — not yet implemented."""
    return {"error": "Week-by-week prediction not yet implemented."}
