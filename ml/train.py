"""
Train one GradientBoosting model per position and save artifacts to models/.

Usage:
  python train.py                          # uses data/fantasy_stats_all.csv
  DATA_PATH=data/custom.csv python train.py

After training, start the server with:
  uvicorn serve:app --reload
"""

import os
import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import GradientBoostingRegressor
from sklearn.model_selection import cross_val_score

from features import POSITIONS, add_computed_columns, add_schedule_strength, add_team_position_share, make_training_pairs, build_X

DATA_PATH   = os.environ.get("DATA_PATH",   "data/fantasy_stats_all.csv")
WEEKLY_PATH = os.environ.get("WEEKLY_PATH", "data/fantasy_weekly_all.csv")
MODEL_DIR   = os.environ.get("MODEL_DIR",   "models")
# Minimum games in the FEATURE season (season N). A per-game rate built from a handful of
# games is noisy input, so those pairs are dropped. The TARGET season (N+1) is deliberately
# NOT filtered on games — excluding injury-shortened outcomes would chop the bottom off the
# outcome distribution and bias every projection upward (elite RBs "only" regress ~13% across
# healthy follow-up seasons vs ~29% once injury years are included). Instead, each pair is
# weighted by how much of the target season the player was available for — see _sample_weights.
MIN_GAMES    = int(os.environ.get("MIN_GAMES", "10"))
MIN_ROWS     = int(os.environ.get("MIN_ROWS",  "20"))   # minimum rows to train a position model
FULL_SEASON  = 17   # NFL regular-season games; target seasons shorter than this are down-weighted


def _sample_weights(pairs: pd.DataFrame) -> np.ndarray:
    """
    Per-pair training weight in (0, 1], from how many games the player logged in the TARGET
    season: a full season counts 1.0, an 8-game (injury/bench) target season ~0.47. This keeps
    genuine decline seasons in the fit while stopping unpredictable missed time — which no
    prior-year box-score feature can foresee — from dominating what the model learns. Pairs
    always have target_games_played >= 1 (the source df drops non-participants), so no weight
    is ever exactly 0.
    """
    g = pairs["target_games_played"].fillna(0).clip(lower=0, upper=FULL_SEASON)
    return (g / FULL_SEASON).to_numpy()


def _make_model() -> GradientBoostingRegressor:
    return GradientBoostingRegressor(
        n_estimators=200,
        max_depth=3,
        learning_rate=0.05,
        subsample=0.8,
        random_state=42,
    )


def train() -> dict:
    os.makedirs(MODEL_DIR, exist_ok=True)

    print(f"Loading {DATA_PATH} …")
    df = pd.read_csv(DATA_PATH)
    print(f"  {len(df):,} rows | seasons: {sorted(df['season'].unique())}")

    # Keep every season a player actually appeared in — the feature-season games filter is
    # applied AFTER the lag join (below) so it only gates season N, not the target season N+1.
    df = df[df["games_played"] >= 1].copy()
    df = add_computed_columns(df)
    df = add_team_position_share(df)

    # Load weekly data for opponent-adjusted schedule strength (optional)
    weekly_df = None
    if os.path.exists(WEEKLY_PATH):
        print(f"Loading {WEEKLY_PATH} for schedule strength ...")
        weekly_df = pd.read_csv(WEEKLY_PATH)
        n_with_opp = weekly_df["opponent_code"].notna().sum()
        print(f"  {len(weekly_df):,} weekly rows | {n_with_opp:,} with opponent_code")
    else:
        print(f"No weekly data at {WEEKLY_PATH} -- opp_pts_allowed will be 0 for all rows")

    df = add_schedule_strength(df, weekly_df)

    training_df = make_training_pairs(df)
    before = len(training_df)
    training_df = training_df[training_df["games_played"] >= MIN_GAMES].copy()
    print(f"  {len(training_df):,} training pairs after lag join "
          f"({before - len(training_df):,} dropped: feature season < {MIN_GAMES} games)\n")

    summary = {}
    for pos in POSITIONS:
        pos_df = training_df[training_df["position"] == pos]
        X = build_X(pos_df, pos)
        y = pos_df["target_points"].values
        w = _sample_weights(pos_df)

        if len(X) < MIN_ROWS:
            print(f"[{pos:<3}] Skipping — only {len(X)} rows (need {MIN_ROWS})")
            continue

        model = _make_model()

        # Rough unweighted sanity check only; the saved model below is fit with target-
        # availability weights, and `train.py eval` (walk-forward) is the honest accuracy metric.
        cv_folds = min(5, len(X))
        scores = cross_val_score(model, X, y, cv=cv_folds, scoring="neg_mean_absolute_error")
        mae, std = -scores.mean(), scores.std()

        model.fit(X, y, sample_weight=w)

        artifact = {"model": model, "features": list(X.columns)}
        path = os.path.join(MODEL_DIR, f"{pos}_model.pkl")
        joblib.dump(artifact, path)

        print(f"[{pos:<3}] {len(X):>4} rows | MAE = {mae:.1f} +/- {std:.1f} pts | saved: {path}")
        _print_importances(model, list(X.columns))
        summary[pos] = {"rows": len(X), "mae": round(mae, 1)}

    print(f"\nTrained {len(summary)}/{len(POSITIONS)} models.")
    return summary


def _print_importances(model: GradientBoostingRegressor, feature_names: list[str],
                       top_n: int = 8) -> None:
    """Print the top N most important features for a trained model."""
    pairs = sorted(zip(feature_names, model.feature_importances_),
                   key=lambda x: x[1], reverse=True)
    total = sum(imp for _, imp in pairs)
    print(f"  {'Feature':<25} {'Importance':>10}  {'Share':>6}")
    print(f"  {'-'*25} {'-'*10}  {'-'*6}")
    cumulative = 0.0
    for name, imp in pairs[:top_n]:
        share = imp / total if total > 0 else 0
        cumulative += share
        print(f"  {name:<25} {imp:>10.4f}  {share:>5.1%}")
    if len(pairs) > top_n:
        remaining = sum(imp for _, imp in pairs[top_n:])
        print(f"  {'... remaining ' + str(len(pairs) - top_n) + ' features':<25} {remaining:>10.4f}  {1-cumulative:>5.1%}")
    print()


def evaluate() -> None:
    """
    Walk-forward cross-validation: always train on past seasons, test on future.

    This is a much more honest accuracy estimate than random k-fold, because
    the model can never see future data during training — just like production.

    Example folds with 2020-2025 data:
      Train 2020-2021 → Test 2022
      Train 2020-2022 → Test 2023
      Train 2020-2023 → Test 2024
      Train 2020-2024 → Test 2025
    """
    print(f"Loading {DATA_PATH} ...")
    df = pd.read_csv(DATA_PATH)
    df = df[df["games_played"] >= 1].copy()
    df = add_computed_columns(df)
    df = add_team_position_share(df)
    weekly_df = pd.read_csv(WEEKLY_PATH) if os.path.exists(WEEKLY_PATH) else None
    df = add_schedule_strength(df, weekly_df)
    training_df = make_training_pairs(df)
    # Feature-season filter only (season N); target season handled via sample weights — mirrors train().
    training_df = training_df[training_df["games_played"] >= MIN_GAMES].copy()

    all_seasons = sorted(training_df["season"].unique())
    # Need at least 2 seasons of training data before first test
    test_seasons = all_seasons[2:]

    print(f"Walk-forward evaluation — test seasons: {test_seasons}\n")
    print(f"{'Pos':<5} {'Train seasons':<18} {'Test':>6} {'N':>5} {'MAE':>8}")
    print("-" * 48)

    overall = []
    for pos in POSITIONS:
        pos_df = training_df[training_df["position"] == pos]
        if pos_df.empty:
            continue

        pos_maes = []
        for test_season in test_seasons:
            train_df = pos_df[pos_df["season"] < test_season]
            test_df  = pos_df[pos_df["season"] == test_season]

            if len(train_df) < MIN_ROWS or test_df.empty:
                continue

            X_train = build_X(train_df, pos)
            y_train = train_df["target_points"].values
            w_train = _sample_weights(train_df)
            X_test  = build_X(test_df,  pos)
            y_test  = test_df["target_points"].values

            m = _make_model()
            m.fit(X_train, y_train, sample_weight=w_train)
            # Test MAE stays unweighted — every real future season counts equally when judging accuracy.
            mae = float(np.mean(np.abs(m.predict(X_test) - y_test)))

            min_yr = int(pos_df["season"].min())
            train_label = f"{min_yr}-{test_season - 1}"
            print(f"{pos:<5} {train_label:<18} {test_season:>6} {len(test_df):>5} {mae:>7.1f}")
            pos_maes.append(mae)
            overall.append(mae)

        if pos_maes:
            print(f"{'':5} {'Average':<18} {'':>6} {'':>5} {np.mean(pos_maes):>7.1f}")
            print()

    if overall:
        print(f"Overall average MAE across all positions: {np.mean(overall):.1f} pts")


if __name__ == "__main__":
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == "eval":
        evaluate()
    else:
        train()
