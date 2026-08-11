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

from features import POSITIONS, add_computed_columns, add_team_position_share, build_X

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
    team: Optional[str] = None
    age: Optional[int] = None
    # Live depth-chart slot as of right now (1 = current starter, 2 = primary backup, ...).
    # Like `status`, Sleeper only ever reports today's depth chart, so this can never be a
    # trained feature -- it's used purely as a prediction-time guardrail (see Guardrail 4).
    depth_chart_order: Optional[int] = None
    # Market signals (Guardrail 5) — live consensus that prices in offseason news no trained
    # feature can see. market_adp: overall average draft position (lower = more valuable,
    # free/keyless Fantasy Football Calculator data). market_points: consensus PPR season
    # projection (FantasyPros, needs a free API key). Neither is ever a trained feature.
    market_adp: Optional[float] = None
    market_points: Optional[float] = None
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
    off_snaps: int = 0
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

    # team_position_share needs the whole request's snap counts grouped by team+position
    # (this player's share of his own position group's snaps on his team), so it's
    # computed once across every player up front rather than per-row.
    all_df = pd.DataFrame([p.model_dump() for p in request.players])
    all_df = add_computed_columns(all_df)
    all_df = add_team_position_share(all_df)

    # adp_trust: this player's ADP percentile within his own position (1.0 = best ADP at the
    # position, 0.0 = worst, 0.5 = no ADP data). Used by Guardrail 5 to scale how much a
    # FantasyPros points gap gets trusted -- two independent external sources agreeing is
    # stronger evidence than either alone, and a big points gap with no ADP support to back
    # it up is more likely one noisy projection than a real signal.
    all_df["adp_trust"] = (1 - all_df.groupby("position")["market_adp"].rank(pct=True)).fillna(0.5)

    predictions = []

    for i, player in enumerate(request.players):
        artifact = _models.get(player.position)
        if artifact is None:
            predictions.append(PlayerPrediction(
                name=player.name,
                position=player.position,
                error=f"No model trained for {player.position}",
            ))
            continue

        df = all_df.iloc[[i]]
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

        # ── Guardrail 2: per-player swing cap ──────────────────────────────────
        # Clamp projection to within 40% of prior season total, in either direction.
        # Allows genuine breakout (or collapse) years while preventing runaway
        # extrapolation from small-sample regression-to-the-mean noise. Symmetric
        # on purpose — team_position_share only sees a teammate's departure via a
        # *current-season* snap split, never next season's roster, so it's still
        # a lagging proxy, not a true forward-looking signal.
        if player.total_points > 0:
            projected = min(projected, player.total_points * 1.40)
            projected = max(projected, player.total_points * 0.60)

        # ── Guardrail 3: current status discount ──────────────────────────────
        # Players currently marked hurt/unrostered get a modest haircut. This is
        # deliberately soft — status is a snapshot taken today, not a guarantee
        # about next season.
        discount = STATUS_DISCOUNTS.get(player.status)
        if discount is not None:
            projected *= discount

        # ── Guardrail 4: live depth-chart nudge ─────────────────────────────────
        # Every trained feature reflects last season's box scores, so none of them can
        # see an offseason departure (a teammate traded/cut/retired). depth_chart_order
        # is pulled fresh from Sleeper at prediction time and is the only forward-looking
        # signal available for "is this player's role currently contested." Deliberately
        # modest and untuned (a heuristic nudge, not a fit parameter) -- and applied AFTER
        # the swing cap on purpose, so a real depth-chart change can move a projection
        # beyond +/-40% when the swing cap's usual justification (no specific reason to
        # believe a big swing) doesn't hold. Only meaningful for committee-prone positions.
        if player.position in ("RB", "WR", "TE") and player.depth_chart_order is not None:
            if player.depth_chart_order == 1:
                projected *= 1.08
            elif player.depth_chart_order >= 3:
                projected *= 0.92

        # ── Guardrail 5: market consensus blend ─────────────────────────────────
        # FantasyPros' consensus projection is an even stronger version of the same idea as
        # Guardrail 4 -- it's forward-looking and bakes in whatever offseason story (departed
        # teammate, scheme change, injury recovery, breakout trajectory) is driving a player's
        # value, none of which any trained feature can see. Pulls toward market_points, scaled
        # by two things: (1) how big the gap actually is -- a player we're already close on
        # barely moves, one we're way off on moves most of the way -- and (2) adp_trust, so a
        # big gap only pulls hard when ADP (an independent source) backs it up; a single
        # possibly-noisy FantasyPros number with no ADP support gets damped, not ignored.
        # Applied last and, like Guardrail 4, allowed to exceed +/-40% for the same reason.
        if player.position in ("QB", "RB", "WR", "TE") and player.market_points and player.market_points > 0:
            gap = (player.market_points - projected) / player.market_points
            base_weight = min(0.50, 1.25 * abs(gap))
            adp_trust = float(df["adp_trust"].iloc[0])
            weight = base_weight * (0.5 + 0.5 * adp_trust)
            projected = projected * (1 - weight) + player.market_points * weight

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
