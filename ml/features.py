"""
Feature engineering shared by train.py and serve.py.

Season CSV column contract (from /admin/export):
  season, position, full_name, team, age, games_played, total_points,
  passing_yds, passing_td, passing_int,
  rushing_yds, rushing_td,
  targets, receiving_rec, receiving_yds, receiving_td, reception_pct, fumbles,
  off_snaps, team_off_snaps, snap_pct,
  pat_made, pat_missed, fg_made,
  def_sacks, def_ints, def_fum_rec, def_td, def_safeties, def_blocked_kicks,
  pts_allowed, yds_allowed

Weekly CSV column contract (from /admin/export/weekly):
  season, week, full_name, position, team_code, opponent_code, total_points
"""

import pandas as pd

POSITIONS = ["QB", "RB", "WR", "TE", "K", "DST"]

# Features fed to each position's model.
# prev2_* = season N-2 stats (two seasons ago), used to capture trend direction.
# has_prev2 = 1 if N-2 data exists, 0 for players entering their second season.
# opp_pts_allowed = avg pts the player's opponents allowed to that position per game;
#   higher means easier schedule, 0 means opponent data not yet backfilled.
POSITION_FEATURES: dict[str, list[str]] = {
    "QB": [
        "age", "age_sq", "games_played", "total_points", "points_per_game",
        "passing_yds", "passing_td", "passing_int",
        "rushing_yds", "rushing_td",
        "snap_pct",
        "prev2_total_points", "prev2_points_per_game", "pts_delta", "has_prev2",
        "weekly_pts_std", "floor", "ceiling", "games_over_10", "games_over_20",
        "opp_pts_allowed",
    ],
    "RB": [
        "age", "age_sq", "games_played", "total_points", "points_per_game",
        "rushing_yds", "rushing_td",
        "targets", "receiving_rec", "receiving_yds", "receiving_td", "reception_pct",
        "snap_pct",
        "prev2_total_points", "prev2_points_per_game", "pts_delta", "has_prev2",
        "weekly_pts_std", "floor", "ceiling", "games_over_10", "games_over_20",
        "opp_pts_allowed",
    ],
    "WR": [
        "age", "age_sq", "games_played", "total_points", "points_per_game",
        "targets", "receiving_rec", "receiving_yds", "receiving_td", "reception_pct",
        "snap_pct",
        "prev2_total_points", "prev2_points_per_game", "pts_delta", "has_prev2",
        "weekly_pts_std", "floor", "ceiling", "games_over_10", "games_over_20",
        "opp_pts_allowed",
    ],
    "TE": [
        "age", "age_sq", "games_played", "total_points", "points_per_game",
        "targets", "receiving_rec", "receiving_yds", "receiving_td", "reception_pct",
        "snap_pct",
        "prev2_total_points", "prev2_points_per_game", "pts_delta", "has_prev2",
        "weekly_pts_std", "floor", "ceiling", "games_over_10", "games_over_20",
        "opp_pts_allowed",
    ],
    "K": [
        "age", "age_sq", "games_played", "total_points", "points_per_game",
        "fg_made", "pat_made", "pat_missed",
        "prev2_total_points", "prev2_points_per_game", "pts_delta", "has_prev2",
        "weekly_pts_std", "floor", "ceiling",
        "opp_pts_allowed",
    ],
    "DST": [
        "games_played", "total_points", "points_per_game",
        "def_sacks", "def_ints", "def_fum_rec", "def_td",
        "def_safeties", "def_blocked_kicks", "pts_allowed",
        "prev2_total_points", "prev2_points_per_game", "pts_delta", "has_prev2",
        "weekly_pts_std", "floor", "ceiling",
    ],
}


def add_computed_columns(df: pd.DataFrame) -> pd.DataFrame:
    """Add derived columns used as features but not stored in the DB."""
    df = df.copy()
    df["points_per_game"] = df["total_points"] / df["games_played"].clip(lower=1)
    df["age_sq"] = df["age"].fillna(0) ** 2
    return df


def add_schedule_strength(season_df: pd.DataFrame,
                          weekly_df: pd.DataFrame) -> pd.DataFrame:
    """
    Add ``opp_pts_allowed`` to season_df.

    Definition: for each player-season, the average pts-per-game that their
    opponents' defenses allowed to the *same position* during that season.

    Higher value  = faced weaker defenses (easier schedule).
    Lower value   = faced stronger defenses (harder schedule).
    0.0           = opponent_code not yet backfilled for this season.

    Algorithm
    ---------
    1. From the weekly CSV, build defensive ratings:
       (season, opponent_code, position) → mean pts allowed per game
    2. Join each player-week to their opponent's rating.
    3. Average per player-season → ``opp_pts_allowed``.
    4. Left-join result into season_df; fill missing with 0.0.
    """
    season_df = season_df.copy()

    if (weekly_df is None
            or weekly_df.empty
            or "opponent_code" not in weekly_df.columns):
        season_df["opp_pts_allowed"] = 0.0
        return season_df

    w = weekly_df.dropna(subset=["opponent_code", "total_points"]).copy()
    if w.empty:
        season_df["opp_pts_allowed"] = 0.0
        return season_df

    # ── Step 1: defensive rating per (season, opp team, position) ──────────
    # "opponent_code" = the defense team the player faced.
    # We group by that team to see how many pts they gave up on average.
    def_ratings = (
        w.groupby(["season", "opponent_code", "position"])["total_points"]
        .mean()
        .reset_index()
        .rename(columns={"total_points": "def_allowed_ppg"})
    )

    # ── Step 2: join each player-week to their opponent's rating ───────────
    w2 = w.merge(
        def_ratings,
        on=["season", "opponent_code", "position"],
        how="left",
    )

    # ── Step 3: average over the season per player ─────────────────────────
    schedule_str = (
        w2.groupby(["full_name", "position", "season"])["def_allowed_ppg"]
        .mean()
        .reset_index()
        .rename(columns={"def_allowed_ppg": "opp_pts_allowed"})
    )

    # ── Step 4: merge into season_df ───────────────────────────────────────
    result = season_df.merge(
        schedule_str,
        on=["full_name", "position", "season"],
        how="left",
    )
    result["opp_pts_allowed"] = result["opp_pts_allowed"].fillna(0.0)
    return result


def make_training_pairs(df: pd.DataFrame) -> pd.DataFrame:
    """
    Build (season N stats + season N-1 stats) → (season N+1 total_points) training rows.

    Primary lag:   season N   → features
    Secondary lag: season N-1 → prev2_* trend features
    Target:        season N+1 total_points

    With 2020-2025 data this produces five pair sets:
      2020->21, 2021->22, 2022->23, 2023->24, 2024->25
    """
    # Primary lag: season N stats paired with season N+1 target
    targets = (
        df[["full_name", "position", "season", "total_points"]]
        .copy()
        .rename(columns={"total_points": "target_points", "season": "next_season"})
    )
    pairs = df.merge(targets, on=["full_name", "position"])
    pairs = pairs[pairs["next_season"] == pairs["season"] + 1].copy()

    # Secondary lag: attach season N-1 stats as prev2_*
    prev2 = (
        df[["full_name", "position", "season", "total_points", "games_played"]]
        .copy()
        .rename(columns={
            "total_points":  "prev2_total_points",
            "games_played":  "prev2_games_played",
            "season":        "prev2_season",
        })
    )
    # join_season is the primary season this prev2 row feeds into (prev2_season + 1)
    prev2["join_season"] = prev2["prev2_season"] + 1

    pairs = pairs.merge(
        prev2[["full_name", "position", "join_season",
               "prev2_total_points", "prev2_games_played"]],
        left_on=["full_name", "position", "season"],
        right_on=["full_name", "position", "join_season"],
        how="left",
    )

    # Derived trend columns — fill NaN for players with no N-2 season
    pairs["has_prev2"] = pairs["prev2_total_points"].notna().astype(int)
    pairs["prev2_total_points"]  = pairs["prev2_total_points"].fillna(0)
    pairs["prev2_games_played"]  = pairs["prev2_games_played"].fillna(0)
    pairs["prev2_points_per_game"] = (
        pairs["prev2_total_points"] / pairs["prev2_games_played"].clip(lower=1)
    )
    # Only meaningful when N-2 data exists — otherwise artificially inflated for first-year players
    pairs["pts_delta"] = (pairs["total_points"] - pairs["prev2_total_points"]) * pairs["has_prev2"]

    return pairs


def build_X(df: pd.DataFrame, position: str) -> pd.DataFrame:
    """
    Return a feature DataFrame for the given position.
    Missing columns are filled with 0 so the function is safe to call
    from serve.py with a single-row DataFrame built from API input.
    """
    cols = POSITION_FEATURES.get(position, [])
    X = pd.DataFrame(index=df.index)
    for col in cols:
        X[col] = df[col].fillna(0) if col in df.columns else 0
    return X
