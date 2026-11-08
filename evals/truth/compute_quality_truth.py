"""Verdad de referencia de los chequeos de calidad (T-023), para comparar data_quality_check (T-025) elemento a elemento.

Se calcula desde la muestra, solo con la biblioteca estándar y sin nada de la aplicación ni de la base de datos.

Reglas:
1. Día = los 10 primeros caracteres de event_time, que viene en UTC («2024-03-01 00:00:00 UTC»): no depende de la zona de la máquina.
2. Nulo = campo vacío en el CSV (así marca el origen los nulos). Se cuentan todas las columnas, también las que dan 0.
3. Duplicado exacto = fila idéntica en las 9 columnas a otra anterior. Se cuentan las copias sobrantes:
   filas - filas distintas (una fila que aparece 3 veces aporta 2).
4. price fuera de rango = price <= 0; se separan cero y negativo. Un price vacío no cuenta aquí (cuenta como nulo).
5. Volumen = filas por día y por event_type, sin quitar duplicados (T-015 exige que lleguen todas las filas).

Uso: python evals/truth/compute_quality_truth.py [--input muestra.csv.gz] [--output quality.json]
"""
import argparse
import csv
import gzip
import json
import sys
from collections import Counter, defaultdict
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT = ROOT / "testdata" / "synthetic-events.csv.gz"
DEFAULT_OUTPUT = Path(__file__).resolve().with_name("quality.json")
COLUMNS = ["event_time", "event_type", "product_id", "category_id", "category_code", "brand", "price", "user_id",
           "user_session"]
RULES = [
    "Día = los 10 primeros caracteres de event_time (UTC).",
    "Nulo = campo vacío; se listan todas las columnas, también con 0.",
    "Duplicados exactos = copias sobrantes de filas idénticas en las 9 columnas (filas - filas distintas).",
    "price fuera de rango = price <= 0, separado en cero y negativo; price vacío cuenta como nulo, no aquí.",
    "Volumen por día y event_type sin quitar duplicados.",
]


def _empty_day():
    return {"rows": 0, "rows_by_event_type": Counter(), "nulls": Counter(), "duplicate_copies": 0,
            "price_zero": 0, "price_negative": 0}


def compute(sample):
    """Devuelve el contenido de quality.json para un CSV comprimido con gzip."""
    days = defaultdict(_empty_day)
    seen = set()
    with gzip.open(sample, "rt", encoding="utf-8", newline="") as f:
        reader = csv.reader(f)
        header = next(reader)
        if header != COLUMNS:
            raise ValueError(f"cabecera inesperada: {header}")
        for row in reader:
            if len(row) != len(COLUMNS):
                raise ValueError(f"fila con {len(row)} campos: {row}")
            record = dict(zip(COLUMNS, row))
            day = days[record["event_time"][:10]]
            day["rows"] += 1
            day["rows_by_event_type"][record["event_type"]] += 1
            for column in COLUMNS:
                if record[column] == "":
                    day["nulls"][column] += 1
            key = tuple(row)
            if key in seen:
                day["duplicate_copies"] += 1
            seen.add(key)
            if record["price"] != "":
                price = Decimal(record["price"])
                if price == 0:
                    day["price_zero"] += 1
                elif price < 0:
                    day["price_negative"] += 1

    event_types = sorted({t for d in days.values() for t in d["rows_by_event_type"]})
    by_day = {}
    for name in sorted(days):
        d = days[name]
        by_day[name] = {
            "rows": d["rows"],
            "rows_by_event_type": {t: d["rows_by_event_type"][t] for t in event_types},
            "nulls": {c: d["nulls"][c] for c in COLUMNS},
            "duplicate_copies": d["duplicate_copies"],
            "price_zero": d["price_zero"],
            "price_negative": d["price_negative"],
            "price_out_of_range": d["price_zero"] + d["price_negative"],
        }

    def total(field):
        return sum(v[field] for v in by_day.values())

    total_rows = total("rows")
    return {
        "source": "testdata/synthetic-events.csv.gz",
        "rules": RULES,
        "totals": {
            "rows": total_rows,
            "distinct_rows": len(seen),
            "duplicate_copies": total("duplicate_copies"),
            "nulls": {c: sum(v["nulls"][c] for v in by_day.values()) for c in COLUMNS},
            "price_zero": total("price_zero"),
            "price_negative": total("price_negative"),
            "price_out_of_range": total("price_out_of_range"),
            "rows_by_event_type": {t: sum(v["rows_by_event_type"][t] for v in by_day.values()) for t in event_types},
        },
        "by_day": by_day,
    }


def render(result):
    return json.dumps(result, ensure_ascii=False, indent=2) + "\n"


def main(argv=None):
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args(argv)
    result = compute(args.input)
    args.output.write_bytes(render(result).encode("utf-8"))  # bytes: igual en Windows y en Linux
    print(json.dumps(result["totals"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
