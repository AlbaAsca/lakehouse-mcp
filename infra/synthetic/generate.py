#!/usr/bin/env python3
"""Genera la muestra sintética de CI: eventos inventados, nunca derivados del CSV real.

Solo biblioteca estándar. Misma semilla y mismas filas dan el mismo fichero byte a byte.
Qué contiene y qué anomalía lleva plantada: README.md de esta carpeta.
"""
import argparse
import csv
import gzip
import io
import random
import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

COLUMNS = ("event_time", "event_type", "product_id", "category_id", "category_code",
           "brand", "price", "user_id", "user_session")
MIN_ROWS = 1000  # por debajo no caben todos los casos sucios ni se aprecia la caída

# Marzo de 2024 queda fuera del periodo del dataset real (oct-2019 a abr-2020).
START = datetime(2024, 3, 1, tzinfo=timezone.utc)
DAYS = 14
SECONDS_PER_DAY = 86400
MAX_SESSION_SECONDS = 3600  # holgado: como mucho 5 eventos separados 5 min

# Anomalía plantada: ese día se ven y se añaden al carrito igual, pero casi no se compra esa marca.
DROP_DAY = datetime(2024, 3, 10, tzinfo=timezone.utc)
DROP_BRAND = "synth-nimbora"
DROP_FACTOR = 0.1

CART_RATE = 0.35
PURCHASE_RATE = 0.5

# Ids en rangos propios, por encima de los del dataset real. None = marca o categoría nula.
PRODUCT_ID_BASE = 900_000_000
USER_ID_BASE = 900_000_000
CATEGORY_ID_BASE = 9_000_000_000_000_000_000
PRODUCTS_PER_BRAND = 40
USERS = 8000
BRANDS = (("synth-nimbora", 0.35), ("synth-velkaro", 0.15), ("synth-ostrina", 0.12),
          ("synth-quorvo", 0.10), ("synth-lumbrel", 0.08), ("synth-tessavi", 0.06),
          ("synth-brondel", 0.04), (None, 0.10))
CATEGORY_CODES = ("synth.gadgets.phone", "synth.gadgets.tablet", "synth.home.kettle",
                  "synth.home.lamp", "synth.garden.hose", "synth.sport.ball",
                  "synth.sport.racket", "synth.toys.kite", None, None)


class Rng:
    """Solo usa random() y getrandbits(): son estables entre versiones de Python, otros métodos no lo garantizan."""

    def __init__(self, seed):
        self._r = random.Random(seed)

    def below(self, n):
        return int(self._r.random() * n)

    def between(self, low, high):
        return low + self.below(high - low + 1)

    def chance(self, p):
        return self._r.random() < p

    def pick(self, seq):
        return seq[self.below(len(seq))]

    def weighted(self, pairs):
        x = self._r.random() * sum(w for _, w in pairs)
        for value, weight in pairs:
            x -= weight
            if x < 0:
                return value
        return pairs[-1][0]

    def sample_indices(self, population, n):
        """n valores distintos de population, en orden de extracción."""
        pool = list(population)
        return [pool.pop(self.below(len(pool))) for _ in range(n)]

    def session_id(self):
        return str(uuid.UUID(int=self._r.getrandbits(128), version=4))


def build_catalog(rng):
    """Por marca: productos con categoría y precio fijos, como en un catálogo de verdad."""
    categories = [(CATEGORY_ID_BASE + i, code) for i, code in enumerate(CATEGORY_CODES, start=1)]
    catalog, next_id = {}, PRODUCT_ID_BASE + 1
    for brand, _ in BRANDS:
        items = []
        for _ in range(PRODUCTS_PER_BRAND):
            category_id, code = rng.pick(categories)
            items.append((next_id, category_id, code, brand, rng.between(199, 149_999)))
            next_id += 1
        catalog[brand] = items
    return catalog


def format_time(ts):
    return ts.strftime("%Y-%m-%d %H:%M:%S UTC")


def format_price(cents):
    sign = "-" if cents < 0 else ""
    return f"{sign}{abs(cents) // 100}.{abs(cents) % 100:02d}"


def generate_session(rng, catalog, day_index):
    """Eventos de una sesión: 1-3 vistas y, a veces, carrito y compra de lo último visto."""
    last_day = day_index == DAYS - 1
    latest_start = SECONDS_PER_DAY - (MAX_SESSION_SECONDS if last_day else 1)
    ts = START + timedelta(days=day_index, seconds=rng.below(latest_start))
    user_id, session = USER_ID_BASE + rng.between(1, USERS), rng.session_id()

    def event(event_type, product):
        product_id, category_id, code, brand, cents = product
        return [ts, event_type, product_id, category_id, code, brand, cents, user_id, session]

    events = []
    product = None
    for _ in range(rng.between(1, 3)):
        product = rng.pick(catalog[rng.weighted(BRANDS)])
        events.append(event("view", product))
        ts += timedelta(seconds=rng.between(5, 300))
    if rng.chance(CART_RATE):
        events.append(event("cart", product))
        ts += timedelta(seconds=rng.between(5, 300))
        rate = PURCHASE_RATE
        if product[3] == DROP_BRAND and ts.date() == DROP_DAY.date():
            rate *= DROP_FACTOR
        if rng.chance(rate):
            events.append(event("purchase", product))
    return events


def generate(seed, rows):
    """Lista de filas de texto ordenada por event_time, con exactamente `rows` filas."""
    if rows < MIN_ROWS:
        raise ValueError(f"rows debe ser al menos {MIN_ROWS}")
    rng = Rng(seed)
    catalog = build_catalog(rng)
    duplicates = rows // 200
    target = rows - duplicates

    events, session_ends = [], []
    while len(events) < target:
        events.extend(generate_session(rng, catalog, rng.below(DAYS)))
        session_ends.append(len(events) - 1)
    del events[target:]  # como mucho recorta el final de la última sesión, que sigue siendo coherente

    # Los casos sucios son por evento y en cantidad exacta, para que aparezcan con cualquier tamaño.
    bad_prices = rng.sample_indices(range(target), rows // 250)
    for position, i in enumerate(bad_prices):
        events[i][6] = 0 if position % 2 == 0 else -events[i][6]
    # Solo el último evento de la sesión pierde el id: así las sesiones siguen siendo coherentes.
    last_events = [i for i in session_ends if i < target]
    for i in rng.sample_indices(last_events, rows // 1000):
        events[i][8] = None

    # Copias exactas, sobre el evento ya ensuciado.
    events.extend([list(events[i]) for i in rng.sample_indices(range(target), duplicates)])
    events.sort(key=lambda e: e[0])  # estable: el orden de los empates también es reproducible
    return [to_row(e) for e in events]


def to_row(event):
    ts, event_type, product_id, category_id, code, brand, cents, user_id, session = event
    return (format_time(ts), event_type, str(product_id), str(category_id), code or "", brand or "",
            format_price(cents), str(user_id), session or "")


def write_gzip_csv(rows, out):
    """Gzip sin nombre de fichero y con mtime=0: el resultado no depende de cuándo ni dónde se genera."""
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "wb") as raw, gzip.GzipFile(filename="", mode="wb", compresslevel=9, fileobj=raw, mtime=0) as gz:
        with io.TextIOWrapper(gz, encoding="utf-8", newline="") as text:
            writer = csv.writer(text, lineterminator="\n")
            writer.writerow(COLUMNS)
            writer.writerows(rows)


def main(argv=None):
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Genera la muestra sintética de eventos para CI.")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--rows", type=int, default=50_000)
    parser.add_argument("--out", default="testdata/synthetic-events.csv.gz")
    args = parser.parse_args(argv)
    if args.rows < MIN_ROWS:
        parser.error(f"--rows debe ser al menos {MIN_ROWS}")
    write_gzip_csv(generate(args.seed, args.rows), args.out)
    print(f"{args.rows} filas en {args.out} (semilla {args.seed})")


if __name__ == "__main__":
    main()
