"""Batería de preguntas de dificultad creciente: ¿hasta dónde llega el servidor MCP con SQL de verdad?

Cada pregunta lleva (1) la consulta que se manda a run_query y (2) una referencia calculada aparte, solo con la biblioteca
estándar, desde testdata/synthetic-events.csv.gz. No usa ningún modelo: mide el servidor y su validador, no al agente.
Reglas comunes: filas tal cual están en la tabla (sin quitar duplicados), salvo que la pregunta diga otra cosa.
Uso: python evals/battery/run.py   (stack arriba y MCP_ANALYST_KEY en .env; escribe evals/battery/report.md)
"""
import csv, gzip, importlib.util, json, sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("mcpdemo", ROOT / "scripts/demo/record_mcp_demo.py")
mcpdemo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mcpdemo)
T = "lh.ecommerce.events"
DAY = "CAST(event_time AS DATE)"  # run_query no admite timezone(); el servidor trabaja en UTC y la referencia lo comprueba


def load():
    rows = []
    with gzip.open(ROOT / "testdata/synthetic-events.csv.gz", "rt", encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            r["t"] = datetime.strptime(r["event_time"].replace(" UTC", ""), "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc)
            r["p"] = float(r["price"]) if r["price"] else 0.0
            rows.append(r)
    return rows


def top(counter, n, key=lambda kv: (-kv[1], kv[0])):
    return [[k, v] for k, v in sorted(counter.items(), key=key)[:n]]


R = load()
purchases = [r for r in R if r["event_type"] == "purchase"]


def q4():
    rev = defaultdict(float)
    for r in purchases:
        if r["category_code"] and r["p"] > 0:
            rev[r["category_code"]] += r["p"]
    return [[k, round(v, 2)] for k, v in sorted(rev.items(), key=lambda kv: (-round(kv[1], 2), kv[0]))[:3]]


def q7():
    views, buys = Counter(), Counter()
    for r in R:
        if r["brand"] and r["event_type"] == "view":
            views[r["brand"]] += 1
        elif r["brand"] and r["event_type"] == "purchase":
            buys[r["brand"]] += 1
    rate = {b: round(buys[b] / v, 4) for b, v in views.items() if v >= 100}
    return [[b, x] for b, x in sorted(rate.items(), key=lambda kv: (-kv[1], kv[0]))[:3]]


def q9():
    first = {}
    for r in sorted((r for r in purchases if r["brand"]), key=lambda r: (r["t"], r["user_session"], r["product_id"])):
        first.setdefault(r["user_id"], r["brand"])
    return top(Counter(first.values()), 3)


def q10():
    seen = defaultdict(set)
    for r in R:
        seen[r["user_session"]].add(r["event_type"])
    return [[sum(1 for s in seen.values() if {"view", "cart", "purchase"} <= s)]]


def q8():
    per = Counter(r["user_id"] for r in purchases)
    return [[sum(1 for v in per.values() if v >= 2)]]


def q11():
    views, carts, buys, rev = Counter(), Counter(), Counter(), defaultdict(float)
    cart_val, bought = defaultdict(float), set()
    for r in R:
        b = r["brand"]
        if not b:
            continue
        if r["event_type"] == "view":
            views[b] += 1
        elif r["event_type"] == "cart":
            carts[b] += 1
            cart_val[(r["user_session"], b)] += r["p"]
        elif r["event_type"] == "purchase":
            buys[b] += 1
            bought.add((r["user_session"], b))
            if r["p"] > 0:
                rev[b] += r["p"]
    lost = defaultdict(float)
    for (sess, b), v in cart_val.items():
        if (sess, b) not in bought:
            lost[b] += v
    total = sum(rev.values())
    out, cum = [], 0.0
    for b, v in sorted(rev.items(), key=lambda kv: (-kv[1], kv[0]))[:5]:
        cum += v
        out.append([b, views[b], carts[b], buys[b], round(buys[b] / views[b], 4), round(v, 2), round(lost[b], 2), round(v / total, 4), round(cum / total, 4)])
    return out


def q12():
    first, days = {}, defaultdict(set)
    for r in purchases:
        days[r["user_id"]].add(r["t"].date())
    for u, ds in days.items():
        first[u] = min(ds)
    cohort = defaultdict(lambda: [0, 0])
    for u, f in first.items():
        cohort[f][0] += 1
        cohort[f][1] += any(d > f for d in days[u])
    return [[str(d), n, ret] for d, (n, ret) in sorted(cohort.items())[:3]]


def q13():
    import statistics
    v, p = {}, {}
    for r in R:
        s = r["user_session"]
        if r["event_type"] == "view":
            v[s] = min(v.get(s, r["t"]), r["t"])
        elif r["event_type"] == "purchase":
            p[s] = min(p.get(s, r["t"]), r["t"])
    mins = lambda t: int(t.timestamp() // 60)
    gaps = [mins(p[s]) - mins(v[s]) for s in p if s in v and p[s] >= v[s]]
    return [[len(gaps), statistics.median(gaps)]]


QUESTIONS = [
    ("Q1", "fácil", "¿Cuántas filas tiene la tabla?", f"SELECT count(*) FROM {T}", lambda: [[len(R)]]),
    ("Q2", "fácil", "¿Cuántos usuarios distintos hay?", f"SELECT count(DISTINCT user_id) FROM {T}", lambda: [[len({r['user_id'] for r in R})]]),
    ("Q3", "fácil", "¿Cuántos eventos hay de cada tipo?", f"SELECT event_type, count(*) AS n FROM {T} GROUP BY event_type ORDER BY n DESC, event_type",
     lambda: top(Counter(r["event_type"] for r in R), 10)),
    ("Q4", "medio", "¿Qué tres categorías ingresan más por compras (precio > 0, con categoría)?",
     f"SELECT category_code, round(sum(price), 2) AS rev FROM {T} WHERE event_type = 'purchase' AND price > 0 AND category_code <> '' GROUP BY category_code ORDER BY rev DESC, category_code LIMIT 3", q4),
    ("Q5", "medio", "¿Qué tres días (UTC) tienen más compras?",
     f"SELECT {DAY} AS d, count(*) AS n FROM {T} WHERE event_type = 'purchase' GROUP BY d ORDER BY n DESC, d LIMIT 3",
     lambda: [[str(k), v] for k, v in top(Counter(r["t"].date() for r in purchases), 3, key=lambda kv: (-kv[1], kv[0]))]),
    ("Q6", "medio", "¿A qué hora del día (UTC) se compra más?",
     f"SELECT extract(hour FROM event_time) AS h, count(*) AS n FROM {T} WHERE event_type = 'purchase' GROUP BY h ORDER BY n DESC, h LIMIT 1",
     lambda: top(Counter(r["t"].hour for r in purchases), 1)),
    ("Q7", "medio", "¿Qué tres marcas, con al menos 100 vistas, convierten mejor de vista a compra?",
     f"SELECT brand, round(sum(CASE WHEN event_type = 'purchase' THEN 1 ELSE 0 END) * 1.0 / sum(CASE WHEN event_type = 'view' THEN 1 ELSE 0 END), 4) AS rate "
     f"FROM {T} WHERE brand <> '' GROUP BY brand HAVING sum(CASE WHEN event_type = 'view' THEN 1 ELSE 0 END) >= 100 ORDER BY rate DESC, brand LIMIT 3", q7),
    ("Q8", "difícil", "¿Cuántos usuarios han comprado dos veces o más?",
     f"SELECT count(*) FROM (SELECT user_id FROM {T} WHERE event_type = 'purchase' GROUP BY user_id HAVING count(*) >= 2)", q8),
    ("Q9", "difícil", "¿Cuál es la marca de la primera compra de cada usuario que más se repite (top 3)? (función de ventana)",
     f"WITH c AS (SELECT user_id, brand, row_number() OVER (PARTITION BY user_id ORDER BY event_time, user_session, product_id) AS rn "
     f"FROM {T} WHERE event_type = 'purchase' AND brand <> '') SELECT brand, count(*) AS n FROM c WHERE rn = 1 GROUP BY brand ORDER BY n DESC, brand LIMIT 3", q9),
    ("Q10", "difícil", "¿Cuántas sesiones tienen vista, carrito y compra (embudo completo)?",
     f"SELECT count(*) FROM (SELECT user_session FROM {T} GROUP BY user_session HAVING sum(CASE WHEN event_type = 'view' THEN 1 ELSE 0 END) > 0 "
     f"AND sum(CASE WHEN event_type = 'cart' THEN 1 ELSE 0 END) > 0 AND sum(CASE WHEN event_type = 'purchase' THEN 1 ELSE 0 END) > 0)", q10),
    ("Q11", "experta", "Cuadro de mando de las 5 marcas con más ingresos: embudo, dinero perdido en carritos abandonados, cuota y Pareto acumulado",
     f"""WITH e AS (SELECT brand, event_type, price, user_session FROM {T} WHERE brand <> ''),
f AS (SELECT brand, sum(CASE WHEN event_type = 'view' THEN 1 ELSE 0 END) AS views, sum(CASE WHEN event_type = 'cart' THEN 1 ELSE 0 END) AS carts,
  sum(CASE WHEN event_type = 'purchase' THEN 1 ELSE 0 END) AS purchases, sum(CASE WHEN event_type = 'purchase' AND price > 0 THEN price ELSE 0 END) AS rev FROM e GROUP BY brand),
s AS (SELECT brand, user_session, sum(CASE WHEN event_type = 'cart' THEN price ELSE 0 END) AS cart_val, sum(CASE WHEN event_type = 'purchase' THEN 1 ELSE 0 END) AS buys FROM e GROUP BY brand, user_session),
a AS (SELECT brand, sum(cart_val) AS lost FROM s WHERE buys = 0 GROUP BY brand)
SELECT brand, views, carts, purchases, round(purchases * 1.0 / views, 4) AS conv, round(rev, 2) AS revenue, round(coalesce(lost, 0), 2) AS lost_in_carts,
  round(rev / sum(rev) OVER (), 4) AS share, round(sum(rev) OVER (ORDER BY rev DESC, brand ROWS UNBOUNDED PRECEDING) / sum(rev) OVER (), 4) AS cum_share
FROM f LEFT JOIN a USING (brand) ORDER BY rev DESC, brand LIMIT 5""".replace("{T}", T), q11),
    ("Q12", "experta", "Retención por cohorte: de los usuarios cuya primera compra fue cada día (3 primeros), ¿cuántos volvieron a comprar otro día?",
     f"WITH f AS (SELECT user_id, min(CAST(event_time AS DATE)) AS first_d FROM {T} WHERE event_type = 'purchase' GROUP BY user_id), "
     f"a AS (SELECT DISTINCT user_id, CAST(event_time AS DATE) AS d FROM {T} WHERE event_type = 'purchase') "
     f"SELECT f.first_d, count(DISTINCT f.user_id) AS n, count(DISTINCT CASE WHEN a.d > f.first_d THEN a.user_id END) AS returned "
     f"FROM f JOIN a USING (user_id) GROUP BY f.first_d ORDER BY f.first_d LIMIT 3", q12),
    ("Q13", "experta", "Mediana de minutos entre la primera vista y la primera compra de una sesión (solo sesiones con ambas)",
     f"WITH s AS (SELECT user_session, min(CASE WHEN event_type = 'view' THEN event_time END) AS v, min(CASE WHEN event_type = 'purchase' THEN event_time END) AS p FROM {T} GROUP BY user_session) "
     f"SELECT count(*) AS n, median(date_diff('minute', v, p)) AS med_min FROM s WHERE v IS NOT NULL AND p IS NOT NULL AND p >= v", q13),
]


def same(a, b):
    if len(a) != len(b):
        return False
    for ra, rb in zip(a, b):
        if len(ra) != len(rb):
            return False
        for x, y in zip(ra, rb):
            if isinstance(x, (int, float)) and isinstance(y, (int, float)):
                if abs(float(x) - float(y)) > 0.011:
                    return False
            elif str(x) != str(y):
                return False
    return True


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    mcp = mcpdemo.Mcp()
    out, lines = [], ["| # | Nivel | Pregunta | Resultado | Detalle |", "|---|---|---|---|---|"]
    for qid, level, text, sql, ref in QUESTIONS:
        expected = ref()
        ok, body = mcp.call("run_query", {"sql": sql})
        if not ok:
            status, detail = "RECHAZADA", body[:90]
        else:
            got = json.loads(body)["rows"]
            status, detail = ("OK", "coincide con la referencia") if same(got, expected) else ("DISTINTA", f"servidor {got} · referencia {expected}")
        lines.append(f"| {qid} | {level} | {text} | {status} | {detail} |")
        print(qid, level, status, detail[:100])
        out.append(status)
    counts = Counter(out)
    summary = f"\n{counts['OK']} de {len(out)} coinciden con la referencia; {counts['RECHAZADA']} rechazadas; {counts['DISTINTA']} con resultado distinto.\n"
    (ROOT / "evals/battery/report.md").write_text("# Batería de preguntas (sin modelo)\n\nGenerado con `python evals/battery/run.py` el "
                                                  + datetime.now().strftime("%Y-%m-%d") + " sobre la muestra sintética de 50 000 filas.\n\n" + "\n".join(lines) + "\n" + summary, encoding="utf-8")
    print(summary)


if __name__ == "__main__":
    main()
