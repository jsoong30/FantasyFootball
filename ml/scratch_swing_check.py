"""
Diagnostic: for players who played a near-full season in BOTH year N and year N+1
(so injury/missed-games isn't muddying the comparison), what does the real
year-over-year point swing distribution actually look like? Answers: is the
guardrail's +/-40% band realistic for healthy players, or too wide?
Not part of the pipeline -- scratch analysis only.
"""
import pandas as pd
import numpy as np

df = pd.read_csv("data/fantasy_stats_all.csv")

MIN_GAMES = 14  # "healthy full season" -- 14+ of 17 games

next_df = df[["full_name", "position", "season", "total_points", "games_played"]].copy()
next_df["season"] = next_df["season"] - 1  # shift back so it joins as "next season" stats
next_df = next_df.rename(columns={
    "total_points": "next_total_points",
    "games_played": "next_games_played",
})

merged = df.merge(next_df, on=["full_name", "position", "season"])
healthy = merged[(merged["games_played"] >= MIN_GAMES) & (merged["next_games_played"] >= MIN_GAMES)].copy()
healthy = healthy[healthy["total_points"] > 50]  # drop scrub-level noise

healthy["pct_change"] = (healthy["next_total_points"] / healthy["total_points"] - 1) * 100

print(f"Healthy-to-healthy pairs (>={MIN_GAMES} games both seasons): {len(healthy)}\n")
for pos in ["QB", "RB", "WR", "TE"]:
    p = healthy[healthy["position"] == pos]["pct_change"]
    if len(p) < 5:
        continue
    print(f"{pos:<3} n={len(p):<4} "
          f"mean={p.mean():>6.1f}%  median={p.median():>6.1f}%  std={p.std():>6.1f}%  "
          f"P10={p.quantile(.10):>6.1f}%  P25={p.quantile(.25):>6.1f}%  "
          f"P75={p.quantile(.75):>6.1f}%  P90={p.quantile(.90):>6.1f}%")
    beyond_40 = (p.abs() > 40).mean() * 100
    print(f"    -> {beyond_40:.1f}% of healthy-to-healthy seasons swing beyond +/-40%")
