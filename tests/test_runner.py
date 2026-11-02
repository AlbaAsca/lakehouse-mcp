import os
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "board"))
import runner  # noqa: E402


def test_pick_skips_contribution_cards():
    t = lambda i, **kw: {"id": i, "owner": "investigador", "status": "doing", "priority": "media", "deps": [], **kw}
    b = {"tasks": [t("T-001", status="next", kind="contrib"), t("T-002", status="backlog")]}
    task, mode = runner.pick(b)
    assert task["id"] == "T-002" and mode == "work"


def _tasks():
    t = lambda i, owner, **kw: {"id": i, "owner": owner, "status": "next", "priority": "media", "deps": [], **kw}
    return {"tasks": [t("T-001", "investigador"), t("T-002", "desarrollador"), t("T-003", "tester", status="review")]}


def test_pick_por_carril():
    b = _tasks()
    assert runner.pick(b, "think")[0]["id"] == "T-001"  # think ignora la review y al desarrollador
    task, mode = runner.pick(b, "measure")
    assert (task["id"], mode) == ("T-003", "verify")  # las verificaciones van siempre a measure
    b["tasks"][2]["status"] = "done"
    assert runner.pick(b, "measure")[0]["id"] == "T-002"
    assert runner.pick(b)[0]["id"] == "T-001"  # all = comportamiento de siempre
    b["tasks"][1]["status"] = "done"
    assert runner.pick(b, "measure") == (None, None)


def test_guarda_de_instancia_por_carril():
    import os
    alive = os.getppid()  # un proceso vivo que no es este (el que lanza pytest)
    assert alive != os.getpid()
    b = {"runner": {"pid": None}, "runners": {"think": {"pid": alive}, "measure": {"pid": None}}}
    assert runner.other_instance(b, "think") == alive
    assert runner.other_instance(b, "measure") is None  # el otro carril no bloquea
    assert runner.other_instance(b, "all") == alive  # all choca con cualquiera
    b["runner"]["pid"], b["runners"]["think"]["pid"] = alive, None
    assert runner.other_instance(b, "measure") == alive  # y cualquiera choca con all
    assert runner.other_instance({"runner": {"pid": os.getpid()}}, "think") is None  # uno mismo no cuenta


def test_commit_solo_con_ficheros_propios(tmp_path, monkeypatch):
    import shutil
    import subprocess
    if not shutil.which("git"):
        return
    g = lambda *a: subprocess.run(["git", *a], cwd=tmp_path, capture_output=True, text=True, check=True).stdout
    g("init", "-q")
    g("config", "user.email", "t@t")
    g("config", "user.name", "t")
    (tmp_path / "board").mkdir()
    for n in ("board/board.json", "otro.txt", "previo.txt"):
        (tmp_path / n).write_text("0", encoding="utf-8")
    g("add", "-A")
    g("commit", "-q", "-m", "init")
    (tmp_path / "previo.txt").write_text("a medias", encoding="utf-8")  # trabajo del otro carril ya en curso
    monkeypatch.setattr(runner, "ROOT", tmp_path)
    before = runner.snapshot()
    assert "previo.txt" in before
    (tmp_path / "mio.py").write_text("x", encoding="utf-8")  # nuevo
    (tmp_path / "otro.txt").write_text("1", encoding="utf-8")  # modificado
    (tmp_path / "board/board.json").write_text("1", encoding="utf-8")
    files = runner.commit_own(before, "chore: test")
    assert files == ["board/board.json", "mio.py", "otro.txt"]
    assert g("show", "--name-only", "--format=", "HEAD").split() == files
    assert "previo.txt" in g("status", "--porcelain")  # sigue sin commitear


def test_limpia_carriles_con_pid_muerto():
    b = {"runner": {"pid": os.getpid(), "state": "working"},
         "runners": {"think": {"pid": 99999999, "state": "working", "current": "T-32"},
                     "measure": {"pid": 8, "state": "working", "current": "T-24"}}}
    runner.clean_dead_lanes(b)
    assert b["runner"]["state"] == "working"  # el vivo (este proceso) no se toca
    for k in ("think", "measure"):
        assert b["runners"][k]["state"] == "stopped" and b["runners"][k]["current"] is None


def test_los_tests_no_tocan_el_board_real():
    assert runner.bd.F.parent != Path(runner.__file__).parent


def test_limite_de_uso_no_es_fallo(monkeypatch):
    r = dict(text="You've hit your weekly limit · resets Oct 20", err="", code=1, secs=5, turns=1,
             cost=None, usage={}, is_error=True)
    assert runner.is_usage_limit(r)
    assert not runner.is_usage_limit(dict(text="RESULT: blocked — faltan datos", err=""))
    calls = []
    fake = {"tasks": [{"id": "T-1", "owner": "desarrollador", "status": "doing"}],
            "agents": [{"id": "desarrollador", "current": "T-1", "status": "working"}]}
    monkeypatch.setattr(runner.bd, "update", lambda f: f(fake))
    monkeypatch.setattr(runner.bd, "task", lambda b, tid: b["tasks"][0])
    monkeypatch.setattr(runner.bd, "set_status", lambda b, t, s, by, msg: (t.update(status=s), calls.append(msg)))
    new, verdict, summary = runner.finish("T-1", "work", "desarrollador", r)
    assert (new, verdict) == ("next", "limit") and "reintenta tras el reset" in summary
    assert fake["tasks"][0]["status"] == "next" and calls == [runner.LIMIT_MSG]
    assert fake["agents"][0]["status"] == "idle"


def _live(denied):
    use = {"type": "assistant", "message": {"content": [
        {"type": "tool_use", "id": "a", "name": "Bash", "input": {"command": "cat x && python board/board.py add T"}}]}}
    txt = "Permission for this tool use was denied" if denied else "ok"
    res = {"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": "a", "content": txt}]}}
    return [json.dumps(use), json.dumps(res)]


def _finish(monkeypatch, denied):
    fake = {"tasks": [{"id": "T-1", "owner": "investigador", "status": "doing", "log": []}], "agents": []}
    monkeypatch.setattr(runner.bd, "update", lambda f: f(fake))
    monkeypatch.setattr(runner.bd, "task", lambda b, tid: b["tasks"][0])
    monkeypatch.setattr(runner.bd, "set_status", lambda b, t, s, by, msg: t.update(status=s))
    monkeypatch.setattr(runner.bd, "log", lambda t, by, msg: t["log"].append(msg))
    r = dict(text="RESULT: done — listo", err="", code=0, secs=5, turns=1, cost=None, usage={}, is_error=False,
             denied=runner.denied_commands(_live(denied)))
    return fake["tasks"][0], runner.finish("T-1", "work", "investigador", r)


def test_denegados_avisan_y_bloquean(monkeypatch):
    t, (new, verdict, _) = _finish(monkeypatch, True)
    assert (new, verdict) == ("blocked", "blocked")
    assert t["log"][0].startswith("⚠ 1 comandos denegados: cat x && python board/board.py add")


def test_sin_denegados_sin_nota(monkeypatch):
    t, (new, verdict, _) = _finish(monkeypatch, False)
    assert (new, verdict) == ("done", "done") and t["log"] == []


def test_runner_no_carga_mcp_del_proyecto():
    import inspect
    assert "--strict-mcp-config" in inspect.getsource(runner.run_claude)
