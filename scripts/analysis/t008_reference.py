"""Independent reference for T-008: one SHA-256 per CSV column of the synthetic sample.

Uses only the Python standard library (no code shared with the Java reader). Each column hashes its values in file
order, one per line, so a change in any single row of a column changes that column's digest.
Pinned in CsvEventReaderReferenceTest; rerun only if the sample changes on purpose.
"""
import csv
import gzip
import hashlib
import sys
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

SAMPLE = Path(__file__).resolve().parents[2] / "testdata" / "synthetic-events.csv.gz"
NULL = "<null>"


def micros(text):
    moment = datetime.strptime(text, "%Y-%m-%d %H:%M:%S UTC").replace(tzinfo=timezone.utc)
    return str(int(moment.timestamp()) * 1_000_000)


def cents(text):
    value = Decimal(text) * 100
    if value != value.to_integral_value():
        raise ValueError(f"price with more than two decimals: {text}")
    return str(int(value))


CANONICAL = {
    "event_time": micros,
    "event_type": str,
    "product_id": lambda v: str(int(v)),
    "category_id": lambda v: str(int(v)),
    "category_code": lambda v: v or NULL,
    "brand": lambda v: v or NULL,
    "price": cents,
    "user_id": lambda v: str(int(v)),
    "user_session": lambda v: v or NULL,
}

digests = {column: hashlib.sha256() for column in CANONICAL}
rows = 0
with gzip.open(SAMPLE, "rt", encoding="utf-8", newline="") as source:
    for row in csv.DictReader(source):
        rows += 1
        for column, convert in CANONICAL.items():
            digests[column].update((convert(row[column]) + "\n").encode("utf-8"))

print(f"rows {rows}")
for column, digest in digests.items():
    print(f'{column} {digest.hexdigest()}')
