"""Respuestas de referencia del examen del agente (T-048).

Parte solo de docs/metrics.md y de testdata/synthetic-events.csv.gz, con la biblioteca estándar. No mira mcp-server/,
agent/ ni semantic/, ni el servidor. Cálculo propio (no reutiliza compute_metrics_truth.py).

Reglas (docs/metrics.md): filas idénticas en las 9 columnas cuentan una; sesión vacía se ignora; solo view/cart/purchase
exactos; día UTC = 10 primeros caracteres de event_time; from/to incluidos. Embudo/abandono: la sesión cuenta en el día de
su primer evento. Dinero: cada compra en su día; válida = purchase con price > 0. Pedido = sesión con compra válida.

Escribe evals/questions/{number,set,ranking}.yaml (JSON, que es YAML válido, para no depender de ninguna biblioteca).
Cada pregunta: id, question, type (number|set|ranking), expected, tolerance, grading, in_subset.
Corrección: number -> |respuesta-expected| <= tolerance; set -> mismos elementos, sin importar el orden;
ranking -> misma lista en el mismo orden y, si hay valor, |valor-esperado| <= tolerance.

Uso: python evals/truth/compute_eval_truth.py [--input muestra.csv.gz] [--outdir evals/questions] [--check]
"""
import argparse
import csv
import gzip
import json
import sys
from collections import defaultdict
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT = ROOT / "testdata" / "synthetic-events.csv.gz"
DEFAULT_OUT = ROOT / "evals" / "questions"
GRADING = {
    "number": "Número dentro de la tolerancia.",
    "set": "Mismos elementos, en cualquier orden.",
    "ranking": "Mismos elementos en el mismo orden; los valores, dentro de la tolerancia.",
    "diagnosis": "Cada grupo de hechos obligatorios aparece (cualquier alias), no aparece ningún texto prohibido y cada "
                 "número esperado coincide con alguno de la respuesta dentro de su tolerancia. Sin modelo como juez.",
}
# Subconjunto barato (~8): E01/E03 (number), E13 (set), E17 (ranking) y 4 diagnósticos que cubren día, marca, embudo y datos sucios
SUBSET = {"E01", "E03", "E13", "E17", "D01", "D02", "D06", "D08"}


def q2(x, exp="0.01"):
    return float(Decimal(x).quantize(Decimal(exp), rounding=ROUND_HALF_UP))


class Data:
    def __init__(self, path):
        seen, self.sess, self.buys = set(), {}, []
        with gzip.open(path, "rt", encoding="utf-8", newline="") as f:
            rd = csv.reader(f)
            next(rd)
            for row in rd:
                t = tuple(row)
                if t in seen:
                    continue
                seen.add(t)
                time, kind, brand, price, sid = row[0], row[1], row[5], row[6], row[8]
                if not sid or kind not in ("view", "cart", "purchase"):
                    continue
                s = self.sess.setdefault(sid, {"first": time, "cart": False, "buy": False, "cartev": False})
                s["first"] = min(s["first"], time)
                s["cartev"] |= kind == "cart"
                s["cart"] |= kind in ("cart", "purchase")
                s["buy"] |= kind == "purchase"
                if kind == "purchase" and price and Decimal(price) > 0:
                    self.buys.append((time[:10], sid, brand, Decimal(price)))

    def sessions(self, a=None, b=None):
        return [s for s in self.sess.values() if (a is None or s["first"][:10] >= a) and (b is None or s["first"][:10] <= b)]

    def purchases(self, a=None, b=None):
        return [p for p in self.buys if (a is None or p[0] >= a) and (b is None or p[0] <= b)]

    def days(self):
        return sorted({s["first"][:10] for s in self.sess.values()} | {p[0] for p in self.buys})

    def revenue(self, a=None, b=None):
        return sum((p[3] for p in self.purchases(a, b)), Decimal(0))

    def ticket(self, a=None, b=None):
        ps = self.purchases(a, b)
        return sum((p[3] for p in ps), Decimal(0)) / len({p[1] for p in ps})

    def by_brand(self, a=None, b=None):
        r = defaultdict(Decimal)
        for _, _, brand, price in self.purchases(a, b):
            if brand:
                r[brand] += price
        return r


def rank(pairs, n):
    return [[k, q2(v)] for k, v in sorted(pairs.items(), key=lambda kv: (-q2(kv[1]), kv[0]))[:n]]


def questions(d):
    rate = lambda n, m: float((Decimal(n) / Decimal(m)).quantize(Decimal("0.000001"), rounding=ROUND_HALF_UP))
    sa = d.sessions()
    s_week1 = d.sessions("2024-03-01", "2024-03-07")
    day_rev = {x: d.revenue(x, x) for x in d.days()}
    day_cart = {x: Decimal(sum(1 for s in d.sessions(x, x) if s["cart"])) for x in d.days()}
    bb = d.by_brand()
    wk1, wk2 = d.by_brand("2024-03-01", "2024-03-07"), d.by_brand("2024-03-08", "2024-03-14")
    N, S, R = "number", "set", "ranking"
    qs = [
        ("E01", N, "¿Qué proporción de las sesiones de todo el periodo acaba en compra (de vista a compra)? Dato entre 0 y 1.",
         rate(sum(s["buy"] for s in sa), len(sa)), 0.000001, True),
        ("E02", N, "¿Cuál fue el ticket medio del 1 al 7 de marzo de 2024, ambos incluidos?",
         q2(d.ticket("2024-03-01", "2024-03-07")), 0.01, True),
        ("E03", N, "¿Cuántos ingresos hubo el 5 de marzo de 2024 (UTC)?", q2(day_rev["2024-03-05"]), 0.01, True),
        ("E04", N, "¿Cuántas sesiones que empezaron el 10 de marzo de 2024 llegaron al carrito (incluidas las que compraron)?",
         int(day_cart["2024-03-10"]), 0, True),
        ("E05", N, "¿Qué proporción de las sesiones con carrito del 1 al 14 de marzo se abandonó sin comprar? Dato entre 0 y 1.",
         rate(sum(s["cart"] and not s["buy"] for s in sa), sum(s["cart"] for s in sa)), 0.000001, True),
        ("E06", N, "¿Cuántas compras válidas (con precio mayor que 0) tiene la marca synth-nimbora en todo el periodo?",
         sum(1 for p in d.purchases() if p[2] == "synth-nimbora"), 0, False),
        ("E07", N, "¿Cuántos ingresos hubo en compras sin marca en todo el periodo?",
         q2(sum((p[3] for p in d.purchases() if not p[2]), Decimal(0))), 0.01, True),
        ("E08", N, "¿Cuánto más (o menos, en negativo) se ingresó el 8 de marzo de 2024 que el 7?",
         q2(day_rev["2024-03-08"] - day_rev["2024-03-07"]), 0.01, False),
        ("E09", N, "¿Cuántas sesiones tienen algún evento de compra pero no cuentan como pedido (porque ninguna de sus compras tiene precio válido), en todo el periodo?",
         len({sid for sid, s in d.sess.items() if s["buy"]} - {p[1] for p in d.purchases()}), 0, False),
        ("E10", N, "¿Cuántos pedidos (sesiones con compra válida) hubo en el periodo, contando cada sesión una sola vez?",
         len({p[1] for p in d.purchases()}), 0, True),
        ("E11", N, "¿Cuántas sesiones empezaron la primera semana (1 al 7 de marzo) y llegaron a compra, aunque comprasen después del 7?",
         sum(s["buy"] for s in s_week1), 0, False),
        ("E12", S, "¿En qué días (fechas AAAA-MM-DD) el ticket medio superó 800?",
         [x for x in d.days() if d.purchases(x, x) and d.ticket(x, x) > 800], None, True),
        ("E13", S, "¿Qué marcas ingresaron más de 20000 en todo el periodo?", sorted(k for k, v in bb.items() if v > 20000), None, True),
        ("E14", S, "¿En qué días (fechas AAAA-MM-DD) se ingresó menos de 40000?",
         [x for x in d.days() if day_rev[x] < 40000], None, False),
        ("E15", R, "¿Cuáles son las 3 marcas que más ingresaron en todo el periodo? Indica marca e ingresos.", rank(bb, 3), 0.01, True),
        ("E16", R, "¿Cuáles son las 3 marcas que más ingresaron del 8 al 14 de marzo? Indica marca e ingresos.",
         rank(wk2, 3), 0.01, False),
        ("E17", R, "¿Cuáles son los 3 días con más ingresos? Indica fecha e ingresos.", rank(day_rev, 3), 0.01, True),
        ("E18", R, "¿Cuáles son los 3 días con más sesiones que llegaron al carrito (por día de inicio de la sesión)? Indica fecha y sesiones.",
         [[k, int(v)] for k, v in sorted(day_cart.items(), key=lambda kv: (-kv[1], kv[0]))[:3]], 0, False),
    ]
    out = {N: [], S: [], R: []}
    for qid, typ, text, exp, tol, subset in qs:
        item = {"id": qid, "question": text, "type": typ, "expected": exp, "grading": GRADING[typ], "in_subset": subset}
        if tol is not None:
            item["tolerance"] = tol
        out[typ].append(item)
    return out


def norm(text):
    return " ".join(text.lower().replace("*", "").split())


def numbers_in(text):
    """Números del texto; acepta 1.234,5 / 1,234.5 / 12,5 / 12.5 y un % pegado."""
    import re
    out = []
    for tok in re.findall(r"-?\d[\d.,]*", text):
        tok = tok.rstrip(".,")
        if "," in tok and "." in tok:
            tok = tok.replace(".", "").replace(",", ".") if tok.rfind(",") > tok.rfind(".") else tok.replace(",", "")
        elif tok.count(".") > 1 or (tok.count(".") == 1 and len(tok.split(".")[1]) == 3 and "," not in tok):
            tok = tok.replace(".", "")
        elif tok.count(",") > 1 or (tok.count(",") == 1 and len(tok.split(",")[1]) == 3):
            tok = tok.replace(",", "")
        else:
            tok = tok.replace(",", ".")
        try:
            out.append(float(tok))
        except ValueError:
            pass
    return out


def grade_diagnosis(item, answer):
    """Corrección fija, sin modelo como juez (decisión 16).
    expected = {required: [[alias, ...], ...], forbidden: [texto, ...], numbers: [{label, value, tolerance}]}.
    Pasa si: de cada grupo de required aparece algún alias; no aparece ningún forbidden; cada número esperado
    tiene algún número del texto a distancia <= tolerance. Devuelve (ok, motivos_de_fallo)."""
    e, t, why = item["expected"], norm(answer), []
    for group in e["required"]:
        if not any(norm(a) in t for a in group):
            why.append("falta: " + " / ".join(group))
    for f in e["forbidden"]:
        if norm(f) in t:
            why.append("prohibido: " + f)
    nums = numbers_in(answer)
    for n in e["numbers"]:
        if not any(abs(x - n["value"]) <= n["tolerance"] for x in nums):
            why.append(f"número: {n['label']} = {n['value']} ± {n['tolerance']}")
    return not why, why


def diagnosis(path):
    """Hechos plantados en la muestra, calculados aquí sobre el CSV (sin mirar agente ni servidor)."""
    with gzip.open(path, "rt", encoding="utf-8", newline="") as f:
        rd = csv.reader(f)
        next(rd)
        raw = [tuple(r) for r in rd]
    rows = list(dict.fromkeys(raw))
    buy, cart = defaultdict(lambda: defaultdict(int)), defaultdict(lambda: defaultdict(int))
    view = defaultdict(lambda: defaultdict(int))
    for r in rows:
        day, kind, brand = r[0][:10], r[1], r[5]
        if kind == "purchase" and r[6] and Decimal(r[6]) > 0:
            buy[brand][day] += 1
        elif kind == "cart":
            cart[brand][day] += 1
        elif kind == "view":
            view[brand][day] += 1
    days = sorted({r[0][:10] for r in rows})

    def median(xs):
        xs = sorted(xs)
        return (xs[len(xs) // 2] + xs[(len(xs) - 1) // 2]) / 2

    # Marca y día con la peor caída relativa frente a su mediana de los demás días
    worst = None
    for b in buy:
        if not b:
            continue
        for dy in days:
            others = median([buy[b][x] for x in days if x != dy])
            if others >= 10:
                ratio = buy[b][dy] / others
                if worst is None or ratio < worst[0]:
                    worst = (ratio, b, dy, others)
    ratio, brand, day, med = worst
    n_day = buy[brand][day]
    fall = round((1 - n_day / med) * 100, 1)
    med_cart = median([cart[brand][x] for x in days if x != day])
    med_view = median([view[brand][x] for x in days if x != day])
    dup = len(raw) - len(rows)
    no_brand = sum(1 for r in raw if not r[5])
    no_sess = sum(1 for r in raw if not r[8])
    zero = sum(1 for r in raw if r[6] and Decimal(r[6]) == 0)
    neg = sum(1 for r in raw if r[6] and Decimal(r[6]) < 0)
    d_iso = day
    d_es = f"{int(day[8:])} de marzo"
    alias_day = [d_iso, d_es, f"{int(day[8:])}/03", f"{day[8:]}/03"]
    N = lambda label, v, tol=0: {"label": label, "value": v, "tolerance": tol}
    no_fall = ["no hay ninguna caída", "no hubo ninguna caída", "no se detecta ninguna caída", "no hay anomalías"]
    # (id, texto, required, forbidden, numbers, in_subset)
    return [
        ("D01", "¿Qué día se desplomaron las compras de alguna marca? Indica la fecha.",
         [alias_day], no_fall, [], True),
        ("D02", "¿En qué marca (segmento) se concentra la caída de compras? Indica la marca.",
         [[brand]], no_fall, [], True),
        ("D03", f"¿Cuántas compras válidas (precio mayor que 0, sin duplicados) tuvo la marca {brand} el {d_es} de 2024?",
         [[brand]], no_fall, [N("compras de la marca ese día", n_day)], True),
        ("D04", f"¿Cuánto cayeron, en porcentaje, las compras de {brand} ese día frente a su mediana de los demás días? Redondea al entero.",
         [[brand]], no_fall, [N("caída en %", fall, 3)], False),
        ("D05", f"¿Cuál es la mediana diaria de compras válidas de {brand} en los demás días del periodo?",
         [], no_fall, [N("mediana de compras", med, 2)], False),
        ("D06", f"¿Dónde del embudo se perdieron las ventas de {brand} ese día: en las vistas, en el carrito o al pagar? "
                f"Compara con los demás días.",
         [["pago", "pagar", "compra", "checkout", "cart a purchase", "carrito a compra", "del carrito"]],
         ["las vistas cayeron", "cayeron las vistas", "las vistas bajaron", "se perdieron las vistas",
          "no se añadió al carrito", "no hubo carritos"], [], True),
        ("D07", f"¿Cuántos carritos (eventos cart sin duplicados) tuvo {brand} ese día? ¿Fue un día normal para esa marca?",
         [["normal", "habitual", "similar", "en línea", "dentro del rango", "no cayó", "no cayeron", "sin caída"]],
         ["también cayeron los carritos", "carritos cayeron"], [N("carritos de la marca ese día", cart[brand][day])], False),
        ("D08", "¿Hay filas duplicadas exactas en los datos? Di cuántas copias sobrantes hay.",
         [["duplicad"]], ["no hay duplicados", "no hay filas duplicadas", "sin duplicados"], [N("copias sobrantes", dup)], True),
        ("D09", "¿Cuántas filas no tienen marca (brand vacía), contando todas las filas sin quitar duplicados?",
         [], [], [N("filas sin marca", no_brand)], False),
        ("D10", "¿Hay sesiones sin identificar (user_session vacío)? Di cuántas filas, sin quitar duplicados.",
         [["user_session", "sesión", "sesion", "sesiones"]], ["no hay sesiones sin identificar", "todas las filas tienen sesión"],
         [N("filas sin user_session", no_sess)], False),
        ("D11", "¿Hay precios fuera de rango? Di cuántas filas tienen precio 0 y cuántas negativo, sin quitar duplicados.",
         [["cero"], ["negativ"]], ["no hay precios", "todos los precios son válidos"],
         [N("precio 0", zero), N("precio negativo", neg)], True),
        ("D12", "¿Es fiable la cifra de compras de ese día, o hay datos sucios que la distorsionen? "
                "Indica el número de filas duplicadas exactas del periodo.",
         [["duplicad"]], ["los datos están limpios", "no hay datos sucios"], [N("copias sobrantes", dup)], False),
    ]


def diagnosis_items(d_rows):
    out = []
    for qid, text, req, forb, nums, subset in d_rows:
        out.append({"id": qid, "question": text, "type": "diagnosis",
                    "expected": {"required": req, "forbidden": forb, "numbers": nums},
                    "grading": GRADING["diagnosis"], "in_subset": subset})
    return out


def render(items):
    return json.dumps(items, ensure_ascii=False, indent=2) + "\n"


def main(argv=None):
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    ap.add_argument("--outdir", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--check", action="store_true", help="no escribe; falla si los ficheros no están al día")
    a = ap.parse_args(argv)
    out = questions(Data(a.input))
    out["diagnosis"] = diagnosis_items(diagnosis(a.input))
    for items in out.values():  # subconjunto barato: uno por tipo y los diagnósticos de una sola consulta
        for q in items:
            q["in_subset"] = q["id"] in SUBSET
    stale = []
    for typ, items in out.items():
        path = a.outdir / f"{typ}.yaml"
        if a.check:
            if not path.exists() or path.read_text(encoding="utf-8") != render(items):
                stale.append(path.name)
        else:
            a.outdir.mkdir(parents=True, exist_ok=True)
            path.write_bytes(render(items).encode("utf-8"))
    if stale:
        print("desactualizado:", ", ".join(stale))
        return 1
    print(f"{sum(len(v) for v in out.values())} preguntas", "al día" if a.check else "escritas")
    return 0


if __name__ == "__main__":
    sys.exit(main())
