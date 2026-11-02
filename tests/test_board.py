"""Pruebas mínimas del panel: CLI con bloqueo, tiempos medidos y API del servidor."""
import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[1]


@pytest.fixture()
def board(tmp_path, monkeypatch):
    d = tmp_path / "board"
    shutil.copytree(ROOT / "board", d, ignore=shutil.ignore_patterns("runs", "__pycache__", ".lock"))
    # la copia no debe apuntar al runner real: «Parar» en un test mataría al agente que está trabajando
    b = json.loads((d / "board.json").read_text(encoding="utf-8"))
    b.setdefault("runner", {}).update(pid=None, state="stopped", current=None)
    for st in (b.get("runners") or {}).values():  # también los carriles: si no, «Parar» mataba al runner real
        st.update(pid=None, state="stopped", current=None)
    (d / "board.json").write_text(json.dumps(b, ensure_ascii=False), encoding="utf-8")
    return d


def cli(d, *args):
    return subprocess.run([sys.executable, str(d / "board.py"), *args], capture_output=True, text=True, check=True).stdout


def test_note_acepta_msg(board):
    tid = cli(board, "add", "N", "--owner", "desarrollador", "--est", "1").strip().split()[-1]
    cli(board, "note", tid, "--msg", "hola", "--by", "tester")
    cli(board, "note", tid, "posicional")
    log = next(t for t in json.loads((board / "board.json").read_text(encoding="utf-8"))["tasks"] if t["id"] == tid)["log"]
    assert [l["msg"] for l in log][-2:] == ["hola", "posicional"]


def test_flujo_de_tarea(board):
    tid = cli(board, "add", "Probar el panel", "--owner", "desarrollador", "--est", "10", "--prio", "alta").strip().split()[-1]
    cli(board, "move", tid, "doing", "--by", "desarrollador")
    cli(board, "move", tid, "review", "--by", "desarrollador", "--msg", "hecho")
    cli(board, "move", tid, "done", "--by", "tester")
    t = next(t for t in json.loads((board / "board.json").read_text(encoding="utf-8"))["tasks"] if t["id"] == tid)
    assert t["status"] == "done" and t["started_at"] and t["finished_at"] and t["actual_min"] is not None


def test_api(board, monkeypatch):
    from fastapi.testclient import TestClient
    sys.path.insert(0, str(board))
    import importlib
    import server
    importlib.reload(server)
    c = TestClient(server.app)
    assert "Panel" in c.get("/").text
    tid = c.post("/api/board/task", json={"title": "Desde la API", "owner": "tester"}).json()["id"]
    assert c.patch(f"/api/board/task/{tid}", json={"status": "next"}).json()["ok"]
    assert any(t["id"] == tid and t["status"] == "next" for t in c.get("/api/board").json()["tasks"])
    assert c.post("/api/board/runner", json={"enabled": True}).json()["enabled"] is True
    assert c.delete(f"/api/board/task/{tid}").json()["ok"]


def test_control_del_runner(board, monkeypatch):
    import os
    from fastapi.testclient import TestClient
    sys.path.insert(0, str(board))
    import importlib
    import server
    importlib.reload(server)
    assert server.pid_alive(os.getpid()) and not server.pid_alive(None)
    c = TestClient(server.app)
    tid = c.post("/api/board/task", json={"title": "En curso", "owner": "desarrollador"}).json()["id"]
    c.patch(f"/api/board/task/{tid}", json={"status": "doing"})
    assert c.post("/api/runner/stop").json()["ok"]
    b = c.get("/api/board").json()
    assert next(t for t in b["tasks"] if t["id"] == tid)["status"] == "next"
    assert b["runner"]["state"] == "stopped" and b["runner"]["alive"] is False
    s = c.get("/api/runner/schedule").json()
    assert "exists" in s and (not s["exists"] or "next" in s)


def test_live_events_resume_el_stream():
    sys.path.insert(0, str(ROOT / "board"))
    import server
    lines = [json.dumps(x) for x in [
        {"type": "system", "subtype": "init", "model": "claude-x"},
        {"type": "assistant", "message": {"content": [{"type": "thinking", "thinking": "miro los folds"},
                                                      {"type": "tool_use", "name": "Bash", "input": {"command": "python scripts/results.py list"}}]}},
        {"type": "user", "message": {"content": [{"type": "tool_result", "content": [{"type": "text", "text": "base-v3 0.39"}]}]}},
        {"type": "result", "result": "RESULT: done — ok"}]] + ["no es json"]
    kinds = [e["kind"] for e in server.live_events(lines)]
    assert kinds == ["inicio", "piensa", "herramienta", "resultado", "fin"]


def test_carriles_en_la_api(board):
    from fastapi.testclient import TestClient
    sys.path.insert(0, str(board))
    import importlib
    import server
    importlib.reload(server)
    c = TestClient(server.app)
    tids = {}
    for o in ("investigador", "desarrollador"):
        tids[o] = c.post("/api/board/task", json={"title": o, "owner": o}).json()["id"]
        c.patch(f"/api/board/task/{tids[o]}", json={"status": "doing"})
    b = c.get("/api/board").json()
    assert all(b["runners"][k]["alive"] is False for k in ("think", "measure"))  # pid=None: no hay procesos reales
    assert c.post("/api/runner/stop?lane=think").json()["ok"]
    st = {t["id"]: t["status"] for t in c.get("/api/board").json()["tasks"]}
    assert st[tids["investigador"]] == "next" and st[tids["desarrollador"]] == "doing"  # solo se toca el carril parado
    assert c.get("/api/runner/live?lane=measure").json()["events"] == []
    assert c.get("/api/runner/live?lane=xx").status_code == 400
    c.post("/api/runner/stop")
    assert next(t for t in c.get("/api/board").json()["tasks"] if t["id"] == tids["desarrollador"])["status"] == "next"
