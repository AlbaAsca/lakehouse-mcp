"""T-222: counts of the dirty cases of the synthetic sample, from the CSV itself (standard library only)."""
import csv
import gzip
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
SAMPLE = Path(__file__).resolve().parents[2] / "testdata" / "synthetic-events.csv.gz"
with gzip.open(SAMPLE, "rt", encoding="utf-8", newline="") as source:
    rows = list(csv.DictReader(source))
prices = [float(r["price"]) for r in rows]
print("rows", len(rows))
print("category_code null", sum(not r["category_code"] for r in rows))
print("brand null", sum(not r["brand"] for r in rows))
print("session null", sum(not r["user_session"] for r in rows))
print("price zero", sum(p == 0.0 for p in prices))
print("price negative", sum(p < 0.0 for p in prices))
print("distinct rows", len({tuple(r.values()) for r in rows}))
