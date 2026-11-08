import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "board"))
import runner  # noqa: E402


def test_small_card_skips_tester():
    assert not runner.needs_tester({"title": "Panel: arreglar un botón", "desc": "texto", "owner": "desarrollador"})


def test_risky_cards_keep_tester():
    assert runner.needs_tester({"title": "Seguridad: roles", "desc": "", "owner": "desarrollador"})
    assert runner.needs_tester({"title": "x", "desc": "prueba de punta a punta", "owner": "desarrollador"})
    assert runner.needs_tester({"title": "x", "desc": "", "owner": "tester"})
    assert runner.needs_tester({"title": "x", "desc": "", "owner": "desarrollador", "verify": True})
