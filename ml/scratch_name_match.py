"""
Diagnostic: how many of our current-season QB/RB/WR/TE players fail to match
FantasyPros/FFC by exact "name|position" key, and does stripping suffixes
(Jr., Sr., II, III, IV) recover most of the misses?
Not part of the pipeline -- scratch analysis only.
"""
import json
import re
import urllib.request
import pandas as pd

def get_json(url, headers=None):
    h = {"User-Agent": "Mozilla/5.0"}
    h.update(headers or {})
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode())

API_KEY = "aXT8wfJaQr5oJCXj82YiO7bj6C68JXCj3opFZJfg"
POSITIONS = ["QB", "RB", "WR", "TE"]

def strip_suffix(name: str) -> str:
    return re.sub(r"\s+(Jr\.?|Sr\.?|I{2,3}|IV)$", "", name).strip()

# Our current (2025) players -- restrict to top 40 per position by total_points, since
# FantasyPros/FFC realistically only cover fantasy-relevant players, not the full bench.
df = pd.read_csv("data/fantasy_stats_all.csv")
ours_all = df[(df["season"] == 2025) & (df["position"].isin(POSITIONS)) & (df["games_played"] >= 4)]
ours = (
    ours_all.sort_values("total_points", ascending=False)
    .groupby("position")
    .head(40)
)
our_names = {(row["full_name"], row["position"]) for _, row in ours.iterrows()}

# FantasyPros projections
fp_names = set()
for pos in POSITIONS:
    data = get_json(
        f"https://api.fantasypros.com/public/v2/json/nfl/2026/projections?position={pos}&scoring=PPR",
        headers={"x-api-key": API_KEY},
    )
    n = len(data.get("players", []))
    print(f"  FantasyPros {pos}: {n} players returned")
    for p in data.get("players", []):
        fp_names.add((p["name"], pos))

# FFC ADP
data = get_json("https://fantasyfootballcalculator.com/api/v1/adp/ppr?teams=12&position=all")
ffc_names = {(p["name"], p["position"]) for p in data.get("players", []) if p["position"] in POSITIONS}

def report(label, external_names):
    exact_misses = [n for n in our_names if n not in external_names]
    ext_stripped = {(strip_suffix(n), pos) for n, pos in external_names}
    still_missing = [n for n in exact_misses if (strip_suffix(n[0]), n[1]) not in ext_stripped]
    recovered = len(exact_misses) - len(still_missing)
    print(f"\n{label}: {len(exact_misses)} exact misses out of {len(our_names)} players")
    print(f"  -> suffix-stripping recovers {recovered}")
    print(f"  -> still missing after stripping: {len(still_missing)}")
    if still_missing:
        print("  sample still-missing:", still_missing[:15])

report("FantasyPros", fp_names)
report("FFC ADP", ffc_names)
