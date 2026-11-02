"""Ningún test debe escribir en el board/board.json real (T-039): se redirige a una copia temporal."""
import shutil
import sys
from pathlib import Path

import pytest

BOARD_DIR = Path(__file__).parents[1] / "board"
sys.path.insert(0, str(BOARD_DIR))
import board as bd  # noqa: E402
import permissions as pm  # noqa: E402


@pytest.fixture(autouse=True)
def _board_aislado(tmp_path_factory, monkeypatch):
    d = tmp_path_factory.mktemp("boardreal")
    if (BOARD_DIR / "board.json").exists():
        shutil.copy(BOARD_DIR / "board.json", d / "board.json")
        import json
        b = json.loads((d / "board.json").read_text(encoding="utf-8"))
        for st in [b.get("runner", {}), *(b.get("runners") or {}).values()]:  # ningún test debe ver procesos reales
            st.update(pid=None)
        (d / "board.json").write_text(json.dumps(b, ensure_ascii=False), encoding="utf-8")
    monkeypatch.setattr(bd, "F", d / "board.json")
    monkeypatch.setattr(bd, "LOCK", d / ".lock")
    monkeypatch.setattr(pm, "F", d / "permissions.json")  # tampoco el registro real de permisos
