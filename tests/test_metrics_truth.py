"""T-029: verdad de referencia de las métricas, contra los ejemplos de docs/metrics.md, la muestra y un recálculo en crudo."""
import gzip
import importlib.util
import json
import subprocess
import sys
from collections import defaultdict
from fractions import Fraction
from pathlib import Path

ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "evals" / "truth" / "compute_metrics_truth.py"
METRICS = ROOT / "evals" / "truth" / "metrics.json"
SAMPLE = ROOT / "testdata" / "synthetic-events.csv.gz"
HEADER = "event_time,event_type,product_id,category_id,category_code,brand,price,user_id,user_session"

spec = importlib.util.spec_from_file_location("metrics_truth", SCRIPT)
truth = importlib.util.module_from_spec(spec)
spec.loader.exec_module(truth)


def run(tmp_path, rows):
    """rows: (time, type, brand, price, session)."""
    lines = [f"{t} UTC,{k},1,2,a.b,{b},{p},3,{s}" for t, k, b, p, s in rows]
    path = tmp_path / "s.csv.gz"
    path.write_bytes(gzip.compress(("\n".join([HEADER] + lines) + "\n").encode("utf-8")))
    return truth.compute(path)


def test_ejemplo_embudo_del_documento(tmp_path):
    r = run(tmp_path, [
        ("2024-03-01 23:50:00", "view", "", "40.00", "s1"),
        ("2024-03-01 23:55:00", "cart", "", "40.00", "s1"),
        ("2024-03-02 00:01:00", "purchase", "", "-5.00", "s1"),
        ("2024-03-01 12:00:00", "view", "", "10.00", "s2"),
        ("2024-03-01 12:05:00", "view", "", "10.00", ""),
    ])
    f = r["by_day"]["2024-03-01"]["funnel"]
    assert (f["view_sessions"], f["cart_sessions"], f["purchase_sessions"]) == (2, 1, 1)
    assert (f["view_to_cart"], f["cart_to_purchase"], f["view_to_purchase"]) == (0.5, 1.0, 0.5)
    assert "2024-03-02" not in r["by_day"]  # sin sesiones ese día: no hay datos


def test_ejemplo_ticket_del_documento(tmp_path):
    r = run(tmp_path, [
        ("2024-03-01 10:00:00", "purchase", "a", "100.00", "s1"),
        ("2024-03-01 10:00:00", "purchase", "a", "100.00", "s1"),  # copia exacta
        ("2024-03-01 10:05:00", "purchase", "a", "50.00", "s1"),
        ("2024-03-01 11:00:00", "purchase", "a", "30.00", "s2"),
        ("2024-03-01 12:00:00", "purchase", "a", "0.00", "s3"),
    ])
    t = r["by_day"]["2024-03-01"]["average_ticket"]
    assert (t["revenue"], t["orders"], t["average_ticket"]) == (180.0, 2, 90.0)


def test_ejemplo_abandono_del_documento(tmp_path):
    r = run(tmp_path, [
        ("2024-03-01 09:00:00", "cart", "", "1.00", "s1"),
        ("2024-03-01 09:10:00", "purchase", "", "1.00", "s1"),
        ("2024-03-01 10:00:00", "cart", "", "1.00", "s2"),
        ("2024-03-01 11:00:00", "purchase", "", "1.00", "s3"),
        ("2024-03-01 12:00:00", "cart", "", "1.00", ""),
    ])
    d = r["by_day"]["2024-03-01"]
    assert d["cart_abandonment"] == {"cart_sessions": 3, "abandoned_sessions": 1, "abandonment_rate": 0.333333}
    assert d["funnel"]["cart_to_purchase"] == 0.666667


def test_ejemplo_marcas_del_documento(tmp_path):
    r = run(tmp_path, [
        ("2024-03-01 09:00:00", "purchase", "beta", "120.00", "s1"),
        ("2024-03-01 10:00:00", "purchase", "alfa", "100.00", "s2"),
        ("2024-03-01 11:00:00", "purchase", "alfa", "20.00", "s3"),
        ("2024-03-01 12:00:00", "purchase", "", "500.00", "s4"),
        ("2024-03-01 13:00:00", "purchase", "gamma", "-10.00", "s5"),
    ])
    b = r["by_day"]["2024-03-01"]["top_brands"]
    assert b["brands"] == [
        {"brand": "alfa", "revenue": 120.0, "purchases": 2, "share": 0.162162},
        {"brand": "beta", "revenue": 120.0, "purchases": 1, "share": 0.162162},
    ]
    assert b["revenue_without_brand"] == 500.0


def test_sesion_con_compras_en_dos_dias(tmp_path):
    """El embudo la pone en su primer día; el dinero, en el día de cada compra; el periodo la cuenta una vez."""
    r = run(tmp_path, [
        ("2024-03-01 23:55:00", "cart", "a", "10.00", "s1"),
        ("2024-03-01 23:58:00", "purchase", "a", "10.00", "s1"),
        ("2024-03-02 00:01:00", "purchase", "a", "20.00", "s1"),
    ])
    assert r["by_day"]["2024-03-01"]["funnel"]["purchase_sessions"] == 1
    assert r["by_day"]["2024-03-02"]["funnel"]["view_sessions"] == 0
    assert r["by_day"]["2024-03-02"]["funnel"]["view_to_cart"] is None
    assert r["by_day"]["2024-03-01"]["average_ticket"]["orders"] == 1
    assert r["by_day"]["2024-03-02"]["average_ticket"]["orders"] == 1
    assert r["total"]["average_ticket"]["orders"] == 1
    assert r["total"]["average_ticket"]["average_ticket"] == 30.0


def test_precio_vacio_y_tipo_desconocido(tmp_path):
    r = run(tmp_path, [
        ("2024-03-01 10:00:00", "purchase", "a", "", "s1"),  # sin precio: compra no válida para el dinero
        ("2024-03-01 10:01:00", "Purchase", "a", "9.00", "s2"),  # mayúscula: tipo ignorado
        ("2024-03-01 10:02:00", "remove_from_cart", "a", "9.00", "s3"),
    ])
    d = r["by_day"]["2024-03-01"]
    assert d["funnel"]["purchase_sessions"] == 1 and d["funnel"]["view_sessions"] == 1
    assert d["average_ticket"] == {"revenue": 0.0, "orders": 0, "average_ticket": None}


def test_dos_ejecuciones_dan_el_mismo_fichero(tmp_path):
    outputs = [tmp_path / "a.json", tmp_path / "b.json"]
    for output in outputs:
        subprocess.run([sys.executable, str(SCRIPT), "--output", str(output)], check=True, capture_output=True)
    assert outputs[0].read_bytes() == outputs[1].read_bytes()


def test_el_fichero_subido_esta_al_dia():
    """Si falla: python evals/truth/compute_metrics_truth.py"""
    assert METRICS.read_text(encoding="utf-8") == truth.render(truth.compute(SAMPLE))


def test_muestra_contra_recalculo_en_crudo():
    """Otra vía: líneas partidas a mano y Fraction, sin csv ni Decimal; incluye cifras de docs/metrics.md."""
    lines = gzip.decompress(SAMPLE.read_bytes()).decode("utf-8").splitlines()[1:]
    sess = defaultdict(lambda: {"t": [], "k": set()})
    money = defaultdict(lambda: defaultdict(Fraction))  # día -> sesión -> importe
    brands = defaultdict(Fraction)
    for line in sorted(set(lines)):
        f = line.split(",")
        if f[8] == "" or f[1] not in ("view", "cart", "purchase"):
            continue
        sess[f[8]]["t"].append(f[0])
        sess[f[8]]["k"].add(f[1])
        if f[1] == "purchase" and f[6] != "" and Fraction(f[6]) > 0:
            money[f[0][:10]][f[8]] += Fraction(f[6])
            brands[f[5]] += Fraction(f[6])
    day_of = {s: min(v["t"])[:10] for s, v in sess.items()}
    assert len(sess) == 19800  # docs/metrics.md
    assert sum(1 for v in sess.values() if min(v["t"])[:10] != max(v["t"])[:10]) == 44  # cruzan medianoche

    q = json.loads(METRICS.read_text(encoding="utf-8"))
    for day, d in q["by_day"].items():
        mine = [v["k"] for s, v in sess.items() if day_of[s] == day]
        carts = sum(1 for k in mine if k & {"cart", "purchase"})
        buys = sum(1 for k in mine if "purchase" in k)
        f = d["funnel"]
        assert (f["view_sessions"], f["cart_sessions"], f["purchase_sessions"]) == (len(mine), carts, buys), day
        assert d["cart_abandonment"]["abandoned_sessions"] == carts - buys, day
        revenue = sum(money[day].values(), Fraction(0))
        assert abs(d["average_ticket"]["revenue"] - float(revenue)) < 0.005, day
        assert d["average_ticket"]["orders"] == len(money[day]), day
    # sumar los días da el periodo, salvo los pedidos (una sesión con compras en dos días)
    for key in ("view_sessions", "cart_sessions", "purchase_sessions"):
        assert sum(d["funnel"][key] for d in q["by_day"].values()) == q["total"]["funnel"][key]
    all_orders = {s for m in money.values() for s in m}
    assert q["total"]["average_ticket"]["orders"] == len(all_orders)
    named = {b: v for b, v in brands.items() if b != ""}
    top = sorted(named.items(), key=lambda kv: (-round(kv[1] * 100) / 100, kv[0]))[:10]
    assert [b["brand"] for b in q["total"]["top_brands"]["brands"]] == [b for b, _ in top]
    for got, (_, rev) in zip(q["total"]["top_brands"]["brands"], top):
        assert abs(got["revenue"] - float(rev)) < 0.005
    assert abs(q["total"]["top_brands"]["revenue_without_brand"] - float(brands[""])) < 0.005
    total = q["total"]
    assert abs(total["cart_abandonment"]["abandonment_rate"] + total["funnel"]["cart_to_purchase"] - 1) < 2e-6
