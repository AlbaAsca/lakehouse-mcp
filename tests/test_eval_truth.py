"""T-048: preguntas del examen y respuestas de referencia al día, y cruzadas con la otra verdad (metrics.json)."""
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "evals" / "truth" / "compute_eval_truth.py"
QDIR = ROOT / "evals" / "questions"


def load():
    items = []
    for t in ("number", "set", "ranking", "diagnosis"):
        items += json.loads((QDIR / f"{t}.yaml").read_text(encoding="utf-8"))
    return {q["id"]: q for q in items}


def grader():
    sys.path.insert(0, str(SCRIPT.parent))
    import compute_eval_truth
    return compute_eval_truth.grade_diagnosis


def test_diagnosticos_con_hechos_plantados():
    """Cifras escritas a mano desde infra/synthetic/README.md y el recuento independiente del CSV (T-051)."""
    qs = load()
    nums = lambda i: {n["label"]: n["value"] for n in qs[i]["expected"]["numbers"]}
    assert qs["D01"]["expected"]["required"][0][0] == "2024-03-10"
    assert qs["D02"]["expected"]["required"] == [["synth-nimbora"]]
    assert nums("D03") == {"compras de la marca ese día": 3}
    assert nums("D07") == {"carritos de la marca ese día": 37}
    assert nums("D08") == {"copias sobrantes": 250}
    assert nums("D09") == {"filas sin marca": 7261}
    assert nums("D10") == {"filas sin user_session": 50}
    assert nums("D11") == {"precio 0": 100, "precio negativo": 101}


def test_corrector_de_diagnosticos():
    g, qs = grader(), load()
    ok, why = g(qs["D01"], "La caída fue el **10 de marzo** de 2024.")
    assert ok, why
    assert not g(qs["D01"], "El 9 de marzo.")[0]
    assert not g(qs["D01"], "El 2024-03-10, aunque no hay ninguna caída.")[0]  # prohibido
    assert g(qs["D03"], "Tuvo 3 compras.")[0] is False  # falta la marca
    assert g(qs["D03"], "synth-nimbora tuvo 3 compras válidas.")[0]
    assert not g(qs["D03"], "synth-nimbora tuvo 30 compras.")[0]
    assert g(qs["D04"], "synth-nimbora cayó un 89 %.")[0]
    assert not g(qs["D04"], "synth-nimbora cayó un 50 %.")[0]
    assert g(qs["D09"], "Hay 7.261 filas sin marca.")[0] and g(qs["D09"], "7,261 filas")[0]
    assert not g(qs["D09"], "Hay 7.000 filas sin marca.")[0]
    assert g(qs["D11"], "100 con precio cero y 101 negativos.")[0]
    assert not g(qs["D11"], "100 con precio cero y 0 negativos.")[0]
    assert g(qs["D08"], "Sí, 250 copias duplicadas.")[0]
    assert not g(qs["D08"], "Sin duplicados.")[0]


def test_expected_al_dia():
    r = subprocess.run([sys.executable, str(SCRIPT), "--check"], capture_output=True, text=True, encoding="utf-8")
    assert r.returncode == 0, r.stdout + r.stderr


def test_forma_de_las_preguntas():
    qs = load()
    assert 25 <= len(qs) <= 30
    assert {q["type"] for q in qs.values()} == {"number", "set", "ranking", "diagnosis"}
    for q in qs.values():
        assert q["question"] and q["grading"] and isinstance(q["in_subset"], bool)
        assert q["expected"] not in ([], None), q["id"]  # nada de respuestas triviales
        if q["type"] == "diagnosis":
            e = q["expected"]
            assert set(e) == {"required", "forbidden", "numbers"} and (e["required"] or e["numbers"]), q["id"]
        elif q["type"] != "set":
            assert "tolerance" in q
    sub = [q for q in qs.values() if q["in_subset"]]
    assert 6 <= len(sub) <= 10
    assert {q["type"] for q in sub} == {"number", "set", "ranking", "diagnosis"}


def test_coincide_con_la_verdad_de_metricas():
    qs = load()
    m = json.loads((ROOT / "evals" / "truth" / "metrics.json").read_text(encoding="utf-8"))["total"]
    assert qs["E01"]["expected"] == m["funnel"]["view_to_purchase"]
    assert qs["E05"]["expected"] == m["cart_abandonment"]["abandonment_rate"]
    assert qs["E07"]["expected"] == m["top_brands"]["revenue_without_brand"]
    assert qs["E10"]["expected"] == m["average_ticket"]["orders"]
    assert qs["E15"]["expected"] == [[b["brand"], b["revenue"]] for b in m["top_brands"]["brands"][:3]]
    assert qs["E09"]["expected"] == m["funnel"]["purchase_sessions"] - m["average_ticket"]["orders"]
