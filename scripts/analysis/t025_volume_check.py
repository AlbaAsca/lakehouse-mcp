"""T-025: independent volume-drop check from evals/truth/quality.json (median of the 7 previous days, per event type)."""
import json
import statistics
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
truth = json.loads((Path(__file__).resolve().parents[2] / "evals/truth/quality.json").read_text(encoding="utf-8"))
days = sorted(truth["by_day"])
for i, day in enumerate(days):
    history = days[max(0, i - 7):i]
    if len(history) < 3:
        continue
    for t, n in truth["by_day"][day]["rows_by_event_type"].items():
        med = statistics.median(truth["by_day"][h]["rows_by_event_type"].get(t, 0) for h in history)
        ratio = n / med
        if ratio < 0.95:
            print(day, t, n, med, round(ratio, 3), "DROP" if ratio < 0.7 else "")
# sum checks: per-day figures add up to totals
tot = truth["totals"]
assert sum(truth["by_day"][d]["rows"] for d in days) == tot["rows"]
assert sum(truth["by_day"][d]["duplicate_copies"] for d in days) == tot["duplicate_copies"]
for c, v in tot["nulls"].items():
    assert sum(truth["by_day"][d]["nulls"][c] for d in days) == v, c
print("days", days[0], days[-1], len(days), "sums OK")
