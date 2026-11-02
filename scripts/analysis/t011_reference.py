"""Independent reference for T-011: which rows a replay delivers, in which order, and when.

Uses only the Python standard library and exact integer arithmetic; shares no code with the Java producer. The hash is
the one the replay promises to keep stable across runs, rewritten from its definition (FNV-1a 64 + murmur3 finalizer,
top 20 bits). Times and order do not depend on it: they come straight from the CSV.

Each scenario prints a row count, a late count and the SHA-256 of one line per delivered row:
    <row index in the file>,<event_time as delivered>,<nanoseconds after the start>
so a change in any single row changes the digest. Pinned in EventReplayReferenceTest; rerun only if the sample changes
on purpose.
"""
import csv
import gzip
import hashlib
import heapq
import sys
from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal
from fractions import Fraction
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

SAMPLE = Path(__file__).resolve().parents[2] / "testdata" / "synthetic-events.csv.gz"
BUCKETS = 1 << 20
MASK = (1 << 64) - 1
# 2026-11-02T12:00:00.123456789Z in whole microseconds.
NOW_MICROS = int(datetime(2026, 11, 2, 12, tzinfo=timezone.utc).timestamp()) * 1_000_000 + 123_456


def bucket(text):
    value = 0xCBF29CE484222325
    for byte in text.encode("utf-8"):
        value = ((value ^ byte) * 0x100000001B3) & MASK
    value ^= value >> 33
    value = (value * 0xFF51AFD7ED558CCD) & MASK
    value ^= value >> 33
    value = (value * 0xC4CEB9FE1A85EC53) & MASK
    value ^= value >> 33
    return value >> 44


def micros(text):
    moment = datetime.strptime(text, "%Y-%m-%d %H:%M:%S UTC").replace(tzinfo=timezone.utc)
    return int(moment.timestamp()) * 1_000_000


def java_double(text):
    """Double.toString for the range accepted here; anything outside it stops the script instead of guessing."""
    value = float(text)
    if value != 0 and not 1e-3 <= abs(value) < 1e7:
        raise ValueError(f"price outside the range where Python and Java print doubles alike: {text}")
    return repr(value)


def key_of(row):
    if row["user_session"]:
        return row["user_session"]
    return "\u0001".join(
        [
            str(micros(row["event_time"])),
            row["event_type"],
            str(int(row["product_id"])),
            str(int(row["category_id"])),
            row["category_code"] or "null",
            row["brand"] or "null",
            java_double(row["price"]),
            str(int(row["user_id"])),
            "null",
        ]
    )


with gzip.open(SAMPLE, "rt", encoding="utf-8", newline="") as source:
    rows = list(csv.DictReader(source))
times = [micros(row["event_time"]) for row in rows]
buckets = [bucket(key_of(row)) for row in rows]


def cutoff_of_rate(rate):
    return int(Fraction(rate) * BUCKETS + Fraction(1, 2))


def cutoff_of_max_rows(max_rows):
    per_bucket = Counter(buckets)
    total = cutoff = 0
    while cutoff < BUCKETS and total + per_bucket[cutoff] <= max_rows:
        total += per_bucket[cutoff]
        cutoff += 1
    return cutoff


def delivery_order(kept, window):
    """Holds back `window` rows and always lets out the oldest, ties in the order they arrived."""
    held = []
    for arrival, index in enumerate(kept):
        heapq.heappush(held, (times[index], arrival, index))
        if len(held) > window:
            yield heapq.heappop(held)[2]
    while held:
        yield heapq.heappop(held)[2]


def round_half_up(value):
    return (2 * value.numerator + value.denominator) // (2 * value.denominator)


def blocks_reversed(size):
    """A disordered file: the sample cut in blocks of `size` rows, each one back to front."""
    feed = []
    for start in range(0, len(rows), size):
        feed.extend(reversed(range(start, min(start + size, len(rows)))))
    return feed


def scenario(name, cutoff, speedup, rewrite, window, feed=None):
    feed = range(len(rows)) if feed is None else feed
    kept = [index for index in feed if buckets[index] < cutoff]
    digest = hashlib.sha256()
    first = latest = None
    late = count = nanos = 0
    for index in delivery_order(kept, window):
        time = times[index]
        if first is None:
            first = latest = time
        late += time < latest
        latest = max(latest, time)
        count += 1
        # The clock never goes back: an event already overdue goes out at the current moment.
        nanos = max(round_half_up(Fraction((time - first) * 1000) / Fraction(speedup)), nanos)
        delivered = time - first + NOW_MICROS if rewrite else time
        digest.update(f"{index},{delivered},{nanos}\n".encode("utf-8"))
    print(f'{name}: count={count} late={late} sha256={digest.hexdigest()}')


unordered = sum(1 for before, after in zip(times, times[1:]) if after < before)
print(f"rows={len(rows)} without_session={sum(not row['user_session'] for row in rows)} step_back={unordered}")

for rate, name in ((0.01, "rate01"), (0.1, "rate10"), (0.5, "rate50")):
    scenario(name, cutoff_of_rate(rate), 1, False, 100_000)
for max_rows in (1, 500, 12_500, 50_000):
    cutoff = cutoff_of_max_rows(max_rows)
    print(f"max{max_rows} cutoff={cutoff}")
    scenario(f"max{max_rows}", cutoff, 1, False, 100_000)
scenario("allSpeed60", BUCKETS, 60, False, 100_000)
scenario("allSpeed7", BUCKETS, 7, False, 100_000)
scenario("allSpeed1234_5", BUCKETS, 1234.5, False, 100_000)
scenario("allHalfSpeed", BUCKETS, 0.5, False, 100_000)
scenario("allRewrite", BUCKETS, 3600, True, 100_000)
scenario("rate10Rewrite", cutoff_of_rate(0.1), 7, True, 100_000)
scenario("allWindow0", BUCKETS, 60, False, 0)
scenario("allWindow10", BUCKETS, 60, True, 10)
scenario("rate50Window1000", cutoff_of_rate(0.5), 60, True, 1_000)
disordered = blocks_reversed(50)
scenario("disorderedWindow49", BUCKETS, 60, True, 49, disordered)
scenario("disorderedWindow48", BUCKETS, 60, True, 48, disordered)
scenario("disorderedWindow0", BUCKETS, 60, True, 0, disordered)
scenario("disorderedRate50", cutoff_of_rate(0.5), 7, False, 100_000, disordered)
