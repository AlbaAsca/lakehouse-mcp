"""T-016: independent figures of the sample, with the standard library only (no DuckDB, no project code)."""
import csv
import gzip
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

SAMPLE = Path(__file__).resolve().parents[2] / "testdata" / "synthetic-events.csv.gz"

with gzip.open(SAMPLE, "rt", encoding="utf-8", newline="") as f:
    rows = list(csv.DictReader(f))

print("rows", len(rows))
print("distinct", len({tuple(r.values()) for r in rows}))
print("category_code null", sum(not r["category_code"] for r in rows))
print("brand null", sum(not r["brand"] for r in rows))
print("user_session null", sum(not r["user_session"] for r in rows))
print("price = 0", sum(float(r["price"]) == 0 for r in rows))
print("price < 0", sum(float(r["price"]) < 0 for r in rows))
print("nimbora purchases 2024-03-10 UTC", sum(
    r["brand"] == "synth-nimbora" and r["event_type"] == "purchase" and r["event_time"].startswith("2024-03-10")
    for r in rows))
print("partitions", len({(r["event_time"][:10], r["event_type"]) for r in rows}))
print("times not in UTC", sum(not r["event_time"].endswith(" UTC") for r in rows))
print("hours of the nimbora purchases around that day", sorted(
    r["event_time"] for r in rows
    if r["brand"] == "synth-nimbora" and r["event_type"] == "purchase" and r["event_time"][:10] in ("2024-03-09", "2024-03-11")
)[:20])
