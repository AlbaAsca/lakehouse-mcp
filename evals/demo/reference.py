"""Verdad de referencia de la pregunta de la demo (T-092): cuántas filas hay y qué cinco marcas ingresan más por compras.

Se calcula desde la muestra, solo con la biblioteca estándar y sin nada de la aplicación ni de la base de datos.

Reglas:
Mismas compras válidas que la métrica top_brands (docs/metrics.md), calculadas aquí aparte y sin usar su YAML:
1. Se quitan los duplicados exactos (filas idénticas en todas las columnas): cada fila distinta cuenta una vez.
2. Solo cuentan compras con precio mayor que 0 y con sesión (user_session) no vacía.
3. Se excluyen las compras con marca vacía: «sin marca» no es una marca que pueda salir en el ranking.
4. Un empate en ingresos se resuelve por nombre de marca, para que el resultado no dependa del orden de lectura.

Uso: python evals/demo/reference.py [--input muestra.csv.gz] [--output expected.json]
"""
import argparse
import csv
import gzip
import json
import sys
from collections import defaultdict
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT = ROOT / "testdata" / "synthetic-events.csv.gz"
DEFAULT_OUTPUT = Path(__file__).resolve().with_name("expected.json")
TOP = 5
RULES = [
    "Se quitan los duplicados exactos (filas idénticas en todas las columnas): cada fila distinta cuenta una vez.",
    "Solo cuentan compras con precio mayor que 0 y con sesión (user_session) no vacía.",
    "Se excluyen las compras con marca vacía.",
    "Un empate en ingresos se resuelve por nombre de marca.",
]


def compute(sample):
    """Devuelve el contenido de expected.json para un CSV comprimido con gzip. Decimal: sumar float acumularía error."""
    total_rows = 0
    revenue = defaultdict(Decimal)
    seen = set()
    with gzip.open(sample, "rt", encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            total_rows += 1
            key = tuple(row.values())
            if key in seen:
                continue
            seen.add(key)
            if row["event_type"] == "purchase" and row["brand"] and row["user_session"] and Decimal(row["price"]) > 0:
                revenue[row["brand"]] += Decimal(row["price"])
    ranking = sorted(revenue.items(), key=lambda item: (-item[1], item[0]))[:TOP]
    return {
        "source": "testdata/synthetic-events.csv.gz",
        "rules": RULES,
        "total_rows": total_rows,
        "top_brands_by_purchase_revenue": [{"brand": brand, "revenue": str(total)} for brand, total in ranking],
    }


def render(result):
    return json.dumps(result, ensure_ascii=False, indent=2) + "\n"


def main(argv=None):
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args(argv)
    text = render(compute(args.input))
    args.output.write_bytes(text.encode("utf-8"))  # bytes: el fichero es igual en Windows y en Linux
    print(text, end="")


if __name__ == "__main__":
    main()
