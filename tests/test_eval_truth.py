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
    for t in ("number", "set", "ranking"):
        items += json.loads((QDIR / f"{t}.yaml").read_text(encoding="utf-8"))
    return {q["id"]: q for q in items}


def test_expected_al_dia():
    r = subprocess.run([sys.executable, str(SCRIPT), "--check"], capture_output=True, text=True, encoding="utf-8")
    assert r.returncode == 0, r.stdout + r.stderr


def test_forma_de_las_preguntas():
    qs = load()
    assert 15 <= len(qs) <= 18
    assert {q["type"] for q in qs.values()} == {"number", "set", "ranking"}
    for q in qs.values():
        assert q["question"] and q["grading"] and isinstance(q["in_subset"], bool)
        assert q["expected"] not in ([], None), q["id"]  # nada de respuestas triviales
        if q["type"] != "set":
            assert "tolerance" in q
    assert 5 <= sum(q["in_subset"] for q in qs.values()) < len(qs)


def test_coincide_con_la_verdad_de_metricas():
    qs = load()
    m = json.loads((ROOT / "evals" / "truth" / "metrics.json").read_text(encoding="utf-8"))["total"]
    assert qs["E01"]["expected"] == m["funnel"]["view_to_purchase"]
    assert qs["E05"]["expected"] == m["cart_abandonment"]["abandonment_rate"]
    assert qs["E07"]["expected"] == m["top_brands"]["revenue_without_brand"]
    assert qs["E10"]["expected"] == m["average_ticket"]["orders"]
    assert qs["E15"]["expected"] == [[b["brand"], b["revenue"]] for b in m["top_brands"]["brands"][:3]]
    assert qs["E09"]["expected"] == m["funnel"]["purchase_sessions"] - m["average_ticket"]["orders"]
