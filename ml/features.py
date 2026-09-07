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
        "age", "age_from_prime_sq", "games_played", "total_points", "points_per_game",
        "passing_yds", "passing_td", "passing_int",
        "rushing_yds", "rushing_td",
        "snap_pct",
        "prev2_total_points", "prev2_points_per_game", "pts_delta", "has_prev2",
        "weekly_pts_std", "floor", "ceiling", "games_over_10", "games_over_20",
        "opp_pts_allowed",
    ],
    "RB": [
        "age", "age_from_prime_sq", "games_played", "total_points", "points_per_game",
        "rushing_yds", "rushing_td",
        "targets", "receiving_rec", "receiving_yds", "receiving_td", "reception_pct",
        "snap_pct", "team_position_share",
        "prev2_total_points", "prev2_points_per_game", "pts_delta", "has_prev2",
        "weekly_pts_std", "floor", "ceiling", "games_over_10", "games_over_20",
        "opp_pts_allowed",
    ],
    "WR": [
        "age", "age_from_prime_sq", "games_played", "total_points", "points_per_game",
        "targets", "receiving_rec", "receiving_yds", "receiving_td", "reception_pct",
        "snap_pct", "team_position_share",
        "prev2_total_points", "prev2_points_per_game", "pts_delta", "has_prev2",
        "weekly_pts_std", "floor", "ceiling", "games_over_10", "games_over_20",
        "opp_pts_allowed",
    ],
    "TE": [
        "age", "age_from_prime_sq", "games_played", "total_points", "points_per_game",
        "targets", "receiving_rec", "receiving_yds", "receiving_td", "reception_pct",
        "snap_pct", "team_position_share",
        "prev2_total_points", "prev2_points_per_game", "pts_delta", "has_prev2",
        "weekly_pts_std", "floor", "ceiling", "games_over_10", "games_over_20",
        "opp_pts_allowed",
    ],
    "K": [
        "age", "age_from_prime_sq", "games_played", "total_points", "points_per_game",
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


PRIME_AGE = 26.5  # typical skill-position physical peak


def add_computed_columns(df: pd.DataFrame) -> pd.DataFrame:
    """Add derived columns used as features but not stored in the DB."""
    df = df.copy()
    df["points_per_game"] = df["total_points"] / df["games_played"].clip(lower=1)
    # age_sq (raw age**2) is monotonically increasing across the whole 20-38 range,
    # so it can never encode "peak at X, decline both directions" -- it can only add
    # curvature to a strictly-increasing-or-decreasing trend. age_from_prime_sq is
    # minimized AT the prime age and rises on both sides, giving the model a direct
    # peak-shaped signal instead of asking it to reconstruct one from raw age with
    # only a few hundred rows per position to learn from.
    age = df["age"].fillna(0)
    df["age_from_prime_sq"] = (age - PRIME_AGE) ** 2
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


def add_team_position_share(df: pd.DataFrame) -> pd.DataFrame:
    """
    Add ``team_position_share`` -- this player's share of offensive snaps
    played among all same-team, same-position teammates that season (0-1).

    This is the closest proxy to "backfield/room competition" we can build
    without a new data source: off_snaps is already synced per player-season.
    A back who played 90% of his team's RB snaps had a clear/uncontested
    role; a back at 50% was in a real committee. It's a current-season
    signal, not a forward-looking one -- it can't know a teammate left in
    the offseason -- but a player who *already* commanded a near-total
    share is a meaningfully different situation than a committee back, and
    that distinction was previously invisible to the model (snap_pct is
    share of the whole *offense*, not share within the position group).
    """
    df = df.copy()
    # Training data spans multiple seasons (group by season+team+position); a live
    # prediction request is implicitly a single season's snapshot, so group by
    # team+position only when there's no season column to split on.
    group_cols = ["season", "team", "position"] if "season" in df.columns else ["team", "position"]
    team_pos_snaps = df.groupby(group_cols)["off_snaps"].transform("sum")
    df["team_position_share"] = (df["off_snaps"] / team_pos_snaps.clip(lower=1)).fillna(0)
    return df


def make_training_pairs(df: pd.DataFrame) -> pd.DataFrame:
    """
    Build (season N stats + season N-1 stats) → (season N+1 total_points) training rows.

    Primary lag:   season N   → features
    Secondary lag: season N-1 → prev2_* trend features
    Target:        season N+1 total_points

    With 2020-2025 data this produces five pair sets:
      2020->21, 2021->22, 2022->23, 2023->24, 2024->25
    """
    # Primary lag: season N stats paired with season N+1 target.
    # target_games_played rides along so train.py can down-weight (not drop) pairs whose target
    # season was injury/bench-shortened — see train.py._sample_weights.
    targets = (
        df[["full_name", "position", "season", "total_points", "games_played"]]
        .copy()
        .rename(columns={"total_points": "target_points",
                         "games_played": "target_games_played",
                         "season": "next_season"})
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
