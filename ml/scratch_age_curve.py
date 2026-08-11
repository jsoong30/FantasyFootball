"""
Diagnostic: hold all features at their position median, vary only age,
and see what shape the trained model actually learned for the aging curve.
Not part of the pipeline -- scratch analysis only.
"""
import joblib
import numpy as np
import pandas as pd

from features import POSITIONS, add_computed_columns, add_schedule_strength, add_team_position_share, make_training_pairs, build_X

df = pd.read_csv("data/fantasy_stats_all.csv")
df = df[df["games_played"] >= 4].copy()
df = add_computed_columns(df)
df = add_team_position_share(df)
weekly_df = pd.read_csv("data/fantasy_weekly_all.csv")
df = add_schedule_strength(df, weekly_df)
training_df = make_training_pairs(df)

for pos in ["RB", "WR", "QB", "TE"]:
    artifact = joblib.load(f"models/{pos}_model.pkl")
    model, feat_cols = artifact["model"], artifact["features"]

    pos_df = training_df[training_df["position"] == pos]
    X = build_X(pos_df, pos)[feat_cols]
    median_row = X.median()

    print(f"\n=== {pos} === (all other features held at position median)")
    print("Age  Projected")
    for age in range(21, 35):
        row = median_row.copy()
        row["age"] = age
        row["age_from_prime_sq"] = (age - 26.5) ** 2
        row["has_prev2"] = 1
        pred = model.predict(pd.DataFrame([row])[feat_cols])[0]
        print(f"{age:>3}  {pred:>9.1f}")
