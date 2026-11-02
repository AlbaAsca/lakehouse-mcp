import io
import os
import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

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


@pytest.fixture
def repo(tmp_path, monkeypatch):
    """Repo git desechable con el runner apuntando a él; devuelve (ruta, g) con g = comando git."""
    if not shutil.which("git"):
        pytest.skip("sin git")
    root = tmp_path / "repo"
    root.mkdir()
    g = lambda *a: subprocess.run(["git", *a], cwd=root, capture_output=True, text=True, check=True).stdout
    g("init", "-q")
    g("config", "user.email", "t@t")
    g("config", "user.name", "t")
    (root / "board").mkdir()
    (root / ".gitignore").write_text("*.log\n", encoding="utf-8")
    for n in ("board/board.json", "otro.txt", "previo.txt"):
        (root / n).write_text("0", encoding="utf-8")
    g("add", "-A")
    g("commit", "-q", "-m", "init")
    monkeypatch.setattr(runner, "ROOT", root)
    monkeypatch.setattr(runner, "CLAIMS", tmp_path / "claims")
    monkeypatch.setattr(runner.time, "sleep", lambda s: None)
    return root, g


def _committed(g):
    return g("show", "--name-only", "--format=", "HEAD").split()


def test_commit_solo_con_ficheros_propios(repo):
    tmp_path, g = repo
    (tmp_path / "previo.txt").write_text("a medias", encoding="utf-8")  # trabajo del otro carril ya en curso
    before = runner.snapshot()
    assert "previo.txt" in before
    (tmp_path / "mio.py").write_text("x", encoding="utf-8")  # nuevo
    (tmp_path / "otro.txt").write_text("1", encoding="utf-8")  # modificado
    (tmp_path / "board/board.json").write_text("1", encoding="utf-8")
    files = runner.commit_own(before, "chore: test")
    assert files == ["board/board.json", "mio.py", "otro.txt"]
    assert g("show", "--name-only", "--format=", "HEAD").split() == files
    assert "previo.txt" in g("status", "--porcelain")  # sigue sin commitear


def test_corte_y_reintento_conservan_los_ficheros_de_la_tarjeta(repo):
    root, g = repo
    # primer intento: el agente escribe y el runner muere antes del commit (■ Parar, reinicio del PC)
    (root / "mio.py").write_text("x", encoding="utf-8")
    runner.claim("T-1", ["mio.py"])
    # reintento: el fichero ya está en `before` y no vuelve a cambiar
    before = runner.snapshot()
    assert "mio.py" in before
    (root / "board/board.json").write_text("1", encoding="utf-8")
    assert runner.commit_own(before, "chore: retry", "T-1") == ["board/board.json", "mio.py"]
    assert _committed(g) == ["board/board.json", "mio.py"]
    assert runner.claims() == {}  # guardado: ya no está pendiente


def test_commit_no_se_lleva_lo_del_otro_carril_ni_lo_pierde(repo):
    root, g = repo
    before = runner.snapshot()
    (root / "board/board.json").write_text("1", encoding="utf-8")
    (root / "mio.py").write_text("x", encoding="utf-8")
    runner.claim("T-1", ["mio.py"])
    (root / "suyo.py").write_text("a medias", encoding="utf-8")  # el otro carril lo crea durante T-1
    runner.claim("T-2", ["suyo.py"])
    (root / "generado.txt").write_text("g", encoding="utf-8")  # salida de un comando de T-1: nadie lo apunta
    assert runner.commit_own(before, "chore: T-1", "T-1") == ["board/board.json", "generado.txt", "mio.py"]
    assert "suyo.py" in g("status", "--porcelain")
    (root / "board/board.json").write_text("2", encoding="utf-8")
    assert runner.commit_own(runner.snapshot(), "chore: T-2", "T-2") == ["board/board.json", "suyo.py"]
    assert _committed(g) == ["board/board.json", "suyo.py"]


def test_commit_incluye_lo_apuntado_aunque_el_estado_de_git_salga_vacio(repo, monkeypatch):
    root, g = repo  # caso T-011: 0b10718 solo llevó board.json con los ficheros creados en esa misma ejecución
    before = runner.snapshot()
    (root / "mio.py").write_text("x", encoding="utf-8")
    (root / "board/board.json").write_text("1", encoding="utf-8")
    runner.claim("T-1", ["mio.py"])
    monkeypatch.setattr(runner, "snapshot", lambda: {})
    runner.commit_own(before, "chore: T-1", "T-1")
    assert _committed(g) == ["board/board.json", "mio.py"]


def test_rutas_apuntadas_que_git_no_admite_no_tumban_el_commit(repo):
    root, g = repo
    before = runner.snapshot()
    (root / "board/board.json").write_text("1", encoding="utf-8")
    (root / "mio.py").write_text("x", encoding="utf-8")
    (root / "salida.log").write_text("ignorado", encoding="utf-8")
    (root / "otro.txt").unlink()  # borrado que sí debe guardarse
    runner.claim("T-1", ["mio.py", "salida.log", "ya-no-existe.py", "otro.txt"])
    runner.commit_own(before, "chore: T-1", "T-1")
    assert _committed(g) == ["board/board.json", "mio.py", "otro.txt"]


def test_sin_cambios_no_es_un_fallo(repo):
    root, g = repo
    runner.claim("T-1", ["otro.txt"])  # un Edit que no llegó a cambiar nada
    assert runner.commit_own(runner.snapshot(), "chore: nada", "T-1") == ["board/board.json", "otro.txt"]
    assert g("log", "--format=%s", "-1").strip() == "init" and runner.claims() == {}


def test_fallo_de_git_conserva_lo_apuntado_y_se_anota_en_la_tarjeta(repo, monkeypatch):
    root, g = repo
    before = runner.snapshot()
    (root / "mio.py").write_text("x", encoding="utf-8")
    (root / "board/board.json").write_text("1", encoding="utf-8")
    runner.claim("T-1", ["mio.py"])
    lock = root / ".git/index.lock"
    lock.write_text("", encoding="utf-8")  # otro proceso con git a medias
    fake = {"tasks": [{"id": "T-1", "log": []}]}
    monkeypatch.setattr(runner.bd, "update", lambda f: f(fake))
    monkeypatch.setattr(runner.bd, "task", lambda b, tid: b["tasks"][0])
    monkeypatch.setattr(runner.bd, "log", lambda t, by, msg: t["log"].append(msg))
    assert runner.save(before, "T-1", "chore: T-1") is False
    assert "commit fallido" in fake["tasks"][0]["log"][0]
    assert runner.claims() == {"T-1": {"mio.py"}}
    lock.unlink()
    assert runner.save(runner.snapshot(), "T-1", "chore: T-1") is True  # el siguiente commit lo recupera
    assert _committed(g) == ["board/board.json", "mio.py"]


def test_git_retry_supera_un_bloqueo_pasajero(repo, monkeypatch):
    root, g = repo
    before = runner.snapshot()
    (root / "board/board.json").write_text("1", encoding="utf-8")
    lock = root / ".git/index.lock"
    lock.write_text("", encoding="utf-8")
    monkeypatch.setattr(runner.time, "sleep", lambda s: lock.unlink(missing_ok=True))  # el otro proceso termina
    runner.commit_own(before, "chore: T-1", "T-1")
    assert _committed(g) == ["board/board.json"]


def _use(name, **inp):
    return json.dumps({"type": "assistant", "message": {"content": [{"type": "tool_use", "id": "a", "name": name, "input": inp}]}})


def test_tool_paths_solo_escrituras_dentro_del_repo(repo, tmp_path):
    root, _ = repo
    assert runner.tool_paths(_use("Write", file_path=str(root / "src" / "A.java"))) == ["src/A.java"]
    assert runner.tool_paths(_use("Edit", file_path="relativo.py")) == ["relativo.py"]
    assert runner.tool_paths(_use("Write", file_path=str(tmp_path / "scratchpad.py"))) == []
    assert runner.tool_paths(_use("Read", file_path=str(root / "a.py"))) == []
    assert runner.tool_paths(_use("Bash", command="python x.py")) == []
    assert runner.tool_paths('{"type":"system","note":"tool_use"}') == []
    assert runner.tool_paths("tool_use sin json") == []


def test_run_claude_apunta_lo_que_escribe_el_agente_al_momento(repo, tmp_path, monkeypatch):
    root, _ = repo

    class FakeProc:
        stdin = io.StringIO()
        stdout = iter([_use("Write", file_path=str(root / "mio.py")) + "\n",
                       _use("Bash", command="ls") + "\n",
                       '{"type":"result","result":"RESULT: review — ok","is_error":false}\n'])
        wait = staticmethod(lambda: 0)
        kill = staticmethod(lambda: None)

    monkeypatch.setattr(runner, "LOGDIR", tmp_path)
    monkeypatch.setattr(runner, "set_runner", lambda **kw: None)
    monkeypatch.setattr(runner.subprocess, "Popen", lambda *a, **kw: FakeProc)
    r = runner.run_claude("claude", "prompt", "desarrollador", 60, tag="T-1")
    assert "RESULT: review" in r["text"]
    assert runner.claims() == {"T-1": {"mio.py"}}


def test_agente_real_muere_a_medias_y_el_reintento_guarda_su_fichero(repo, tmp_path, monkeypatch):
    """T-073 con un proceso de verdad: escribe, muere sin resultado y el reintento no vuelve a tocar el fichero."""
    root, g = repo
    fake = tmp_path / "fake_claude.py"
    fake.write_text(
        "import json, os, sys\n"
        "print(json.dumps({'type': 'assistant', 'message': {'content': [{'type': 'tool_use', 'id': 'a', 'name': 'Write',"
        " 'input': {'file_path': sys.argv[1]}}]}}), flush=True)\n"
        "open(sys.argv[1], 'w').write('x')\n"
        "os._exit(137)\n", encoding="utf-8")
    real_popen = subprocess.Popen
    monkeypatch.setattr(runner, "LOGDIR", tmp_path)
    monkeypatch.setattr(runner, "set_runner", lambda **kw: None)
    with monkeypatch.context() as m:  # solo durante el agente: git() también pasa por Popen
        m.setattr(runner.subprocess, "Popen",
                  lambda cmd, **kw: real_popen([sys.executable, str(fake), str(root / "mio.py")], **kw))
        r = runner.run_claude("claude", "prompt", "desarrollador", 60, tag="T-1")
    assert r["is_error"] and r["code"] == 137
    assert runner.claims() == {"T-1": {"mio.py"}}  # ha sobrevivido a la muerte del agente

    before = runner.snapshot()  # reintento: el fichero ya está aquí y no volverá a cambiar
    assert "mio.py" in before
    (root / "board/board.json").write_text("1", encoding="utf-8")
    runner.commit_own(before, "chore: retry", "T-1")
    assert _committed(g) == ["board/board.json", "mio.py"]
    assert "mio.py" not in g("status", "--porcelain") and runner.claims() == {}


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
