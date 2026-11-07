"""Verdad de referencia de las cuatro métricas (T-029), para comparar el YAML de la capa semántica (T-031).

Parte solo de docs/metrics.md y de la muestra, con la biblioteca estándar; no mira semantic/ ni la base de datos.

Resumen de las reglas (detalle en docs/metrics.md):
- Duplicado = fila idéntica en las 9 columnas (cuenta una vez). Sesión nula (campo vacío) = fila ignorada.
- Solo view/cart/purchase, coincidencia exacta. Día = 10 primeros caracteres de event_time (UTC).
- Embudo y abandono: la sesión pertenece al día de su primer evento; cuentan todos sus eventos.
- Ticket y marcas: cada compra pertenece a su propio día; compra válida = purchase, con sesión, price > 0, sin copias.
  Un price vacío no es > 0, así que no es válido.
- Marca vacía = nula: no entra en el ranking, va a revenue_without_brand.
- Tasas con 6 decimales y dinero con 2, redondeo half-up, solo al presentar (se calcula con Fraction/Decimal).
- Denominador 0 -> null.

Uso: python evals/truth/compute_metrics_truth.py [--input muestra.csv.gz] [--output metrics.json]
"""
import argparse
import csv
import gzip
import json
import sys
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT = ROOT / "testdata" / "synthetic-events.csv.gz"
DEFAULT_OUTPUT = Path(__file__).resolve().with_name("metrics.json")
COLUMNS = ["event_time", "event_type", "product_id", "category_id", "category_code", "brand", "price", "user_id",
           "user_session"]
TYPES = ("view", "cart", "purchase")
TOP = 10
RULES = [
    "Filas idénticas en las 9 columnas cuentan una vez; sesión vacía se ignora; solo view/cart/purchase exactos.",
    "Día = 10 primeros caracteres de event_time (UTC); from/to incluidos.",
    "Embudo y abandono: sesión en el día de su primer evento (view/cart/purchase); cuentan todos sus eventos.",
    "Ticket y marcas: compra en su propio día; válida = purchase con sesión y price > 0 (price vacío no es válido).",
    "Pedido = sesión con compra válida. Marca vacía = nula: va a revenue_without_brand.",
    "Tasas 6 decimales, dinero 2 decimales (half-up); denominador 0 -> null.",
    "Top marcas: ingresos (redondeados) desc, luego brand por punto de código; top 10.",
]


def rate(num, den):
    if den == 0:
        return None
    return _q(Decimal(num) / Decimal(den), "0.000001")


def _q(value, exp):
    return float(Decimal(value).quantize(Decimal(exp), rounding=ROUND_HALF_UP))


def money(value):
    return _q(value, "0.01")


def load(sample):
    """Devuelve (sesiones, compras): sesiones {id: (primer_día, tiene_cart, tiene_purchase)}, compras válidas."""
    sessions = {}
    purchases = []
    seen = set()
    with gzip.open(sample, "rt", encoding="utf-8", newline="") as f:
        reader = csv.reader(f)
        if next(reader) != COLUMNS:
            raise ValueError("cabecera inesperada")
        for row in reader:
            if len(row) != len(COLUMNS):
                raise ValueError(f"fila con {len(row)} campos: {row}")
            key = tuple(row)
            if key in seen:
                continue
            seen.add(key)
            rec = dict(zip(COLUMNS, row))
            if rec["user_session"] == "" or rec["event_type"] not in TYPES:
                continue
            day, kind, sid = rec["event_time"][:10], rec["event_type"], rec["user_session"]
            first = sessions.get(sid)
            s = {"first": (rec["event_time"], day), "cart": False, "purchase": False} if first is None else first
            if rec["event_time"] < s["first"][0]:
                s["first"] = (rec["event_time"], day)
            s["cart"] = s["cart"] or kind in ("cart", "purchase")
            s["purchase"] = s["purchase"] or kind == "purchase"
            sessions[sid] = s
            if kind == "purchase" and rec["price"] != "" and Decimal(rec["price"]) > 0:
                purchases.append((day, sid, rec["brand"] or None, Decimal(rec["price"])))
    return sessions, purchases


def funnel_and_abandon(sessions_in_scope):
    view = len(sessions_in_scope)
    cart = sum(1 for s in sessions_in_scope if s["cart"])
    buy = sum(1 for s in sessions_in_scope if s["purchase"])
    abandoned = sum(1 for s in sessions_in_scope if s["cart"] and not s["purchase"])
    return {
        "funnel": {"view_sessions": view, "cart_sessions": cart, "purchase_sessions": buy,
                   "view_to_cart": rate(cart, view), "cart_to_purchase": rate(buy, cart),
                   "view_to_purchase": rate(buy, view)},
        "cart_abandonment": {"cart_sessions": cart, "abandoned_sessions": abandoned,
                             "abandonment_rate": rate(abandoned, cart)},
    }


def ticket_and_brands(purchases_in_scope):
    revenue = sum((p[3] for p in purchases_in_scope), Decimal(0))
    orders = len({p[1] for p in purchases_in_scope})
    by_brand = {}
    no_brand = Decimal(0)
    for _, _, brand, price in purchases_in_scope:
        if brand is None:
            no_brand += price
            continue
        r = by_brand.setdefault(brand, [Decimal(0), 0])
        r[0] += price
        r[1] += 1
    ranked = sorted(by_brand.items(), key=lambda kv: (-money(kv[1][0]), kv[0]))
    top = [{"brand": b, "revenue": money(r), "purchases": n,
            "share": None if revenue == 0 else _q(r / revenue, "0.000001")} for b, (r, n) in ranked[:TOP]]
    return {
        "average_ticket": {"revenue": money(revenue), "orders": orders,
                           "average_ticket": None if orders == 0 else money(revenue / orders)},
        "top_brands": {"brands": top, "revenue_without_brand": money(no_brand), "brands_total": len(by_brand)},
    }


def compute(sample):
    sessions, purchases = load(sample)
    days = sorted({s["first"][1] for s in sessions.values()} | {p[0] for p in purchases})
    by_day = {}
    for day in days:
        entry = funnel_and_abandon([s for s in sessions.values() if s["first"][1] == day])
        entry.update(ticket_and_brands([p for p in purchases if p[0] == day]))
        by_day[day] = entry
    total = funnel_and_abandon(list(sessions.values()))
    total.update(ticket_and_brands(purchases))
    return {"source": "testdata/synthetic-events.csv.gz", "rules": RULES, "total": total, "by_day": by_day}


def render(result):
    return json.dumps(result, ensure_ascii=False, indent=2) + "\n"


def main(argv=None):
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args(argv)
    result = compute(args.input)
    args.output.write_bytes(render(result).encode("utf-8"))
    print(json.dumps(result["total"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
