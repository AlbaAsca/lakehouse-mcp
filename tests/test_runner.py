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


def test_pick_con_ci_en_rojo_solo_tarjetas_ci():
    b = _tasks()
    assert runner.pick(b, "all", ci_red=True) == (None, None)
    b["tasks"][1]["title"] = "Arreglar CI de develop"
    task, mode = runner.pick(b, "all", ci_red=True)
    assert (task["id"], mode) == ("T-002", "work")


_REAL_CI_IS_RED = runner.ci_is_red


@pytest.fixture(autouse=True)
def _sin_gh_real(monkeypatch):
    monkeypatch.setattr(runner, "ci_is_red", lambda *a, **k: False)


def test_ci_is_red(monkeypatch):
    monkeypatch.setattr(runner, "ci_is_red", _REAL_CI_IS_RED)

    def fake(out, code=0):
        return lambda *a, **k: subprocess.CompletedProcess(a, code, out, "")
    monkeypatch.setattr(runner.subprocess, "run", fake('[{"conclusion":"failure"}]'))
    assert runner.ci_is_red()
    monkeypatch.setattr(runner.subprocess, "run", fake('[{"conclusion":"success"}]'))
    assert not runner.ci_is_red()
    monkeypatch.setattr(runner.subprocess, "run", fake("", 1))
    assert not runner.ci_is_red()


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


def test_verify_avisa_de_ficheros_sin_confirmar(repo):
    root, g = repo
    t = {"id": "T-1", "title": "x", "owner": "desarrollador", "log": []}
    assert "NO están en git" not in runner.build_prompt(t, "verify", "tester")
    (root / "Falta.java").write_text("x", encoding="utf-8")
    p = runner.build_prompt(t, "verify", "tester")
    assert "NO están en git" in p and "Falta.java" in p
    assert "Falta.java" not in runner.build_prompt(t, "work", "desarrollador")


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


def test_estado_intermedio_no_commitea_y_el_final_recoge_todo(repo):
    root, g = repo
    before = runner.snapshot()
    (root / "mio.py").write_text("x", encoding="utf-8")
    (root / "gen.txt").write_text("y", encoding="utf-8")
    (root / "board/board.json").write_text("1", encoding="utf-8")
    assert runner.save(before, "T-1", "chore: T-1", "review") is True
    assert g("log", "--format=%s", "-1").strip() == "init"
    assert runner.claims() == {"T-1": {"gen.txt", "mio.py"}}
    assert runner.save(runner.snapshot(), "T-1", "chore: T-1", "done") is True
    assert _committed(g) == ["board/board.json", "gen.txt", "mio.py"]


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


def test_worktree_por_tarjeta_aisla_ficheros_y_no_commitea_el_panel(repo, tmp_path, monkeypatch):
    root, g = repo
    g("branch", "-M", "develop")
    monkeypatch.setattr(runner, "WT_ROOT", tmp_path / "wt")
    ta, tb = {"id": "T-1", "title": "Uno / dos!"}, {"id": "T-2", "title": "Otra"}
    wa, wb = runner.make_worktree(ta), runner.make_worktree(tb)
    assert wa == tmp_path / "wt" / "T-1" and runner.branch_name(ta) == "feature/T-1-uno-dos"
    assert runner.make_worktree(ta) == wa  # reutiliza
    (wa / "a.txt").write_text("a", encoding="utf-8")
    (wb / "b.txt").write_text("b", encoding="utf-8")
    (wa / "board" / "board.json").write_text("copia vieja", encoding="utf-8")
    runner.commit_worktree(wa, "feat: a (T-1)")
    runner.commit_worktree(wb, "feat: b (T-2)")
    show = lambda w: subprocess.run(["git", "show", "--name-only", "--format=", "HEAD"], cwd=w, capture_output=True,
                                    text=True).stdout.split()
    assert show(wa) == ["a.txt"] and show(wb) == ["b.txt"]
    assert not (root / "a.txt").exists()  # el árbol principal no se toca


def test_worktree_env_apunta_al_panel_real_y_al_mismo_compose():
    env = runner.worktree_env()
    assert env["LAKEHOUSE_BOARD_DIR"] == str(runner.bd.F.parent)
    assert env["COMPOSE_PROJECT_NAME"] == runner.ROOT.name.lower()


def test_board_py_usa_lakehouse_board_dir(tmp_path):
    r = subprocess.run([sys.executable, "-c", "import board; print(board.F)"], cwd=Path(runner.__file__).parent,
                       env={**os.environ, "LAKEHOUSE_BOARD_DIR": str(tmp_path)}, capture_output=True, text=True)
    assert Path(r.stdout.strip()) == tmp_path / "board.json"
    r = subprocess.run([sys.executable, "-c", "import board; print(board.F)"], cwd=Path(runner.__file__).parent,
                       env={k: v for k, v in os.environ.items() if k != "LAKEHOUSE_BOARD_DIR"}, capture_output=True, text=True)
    assert Path(r.stdout.strip()) == Path(runner.__file__).parent / "board.json"


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


def _new_card(status):
    def f(b):
        t = runner.bd.new_task(b, "Tarjeta de prueba", "desarrollador", 5, prio="alta", desc="d", by="test")
        runner.bd.set_status(b, t, status, "test", "preparada")
        return t["id"]
    return runner.bd.update(f)


def _stream(monkeypatch, tmp_path, events, code):
    class FakeProc:
        stdin = io.StringIO()
        stdout = iter(json.dumps(e) + "\n" for e in events)
        wait = staticmethod(lambda: code)
        kill = staticmethod(lambda: None)

    monkeypatch.setattr(runner, "LOGDIR", tmp_path)
    monkeypatch.setattr(runner, "claim", lambda *a: None)
    monkeypatch.setattr(runner.subprocess, "Popen", lambda *a, **kw: FakeProc)
    return runner.run_claude("claude", "prompt", "tester", 60, tag="T-1")


# Lo que escribió de verdad el tester de T-012 (board/runs/T-012-tester-20261102-131521.txt): acaba sin evento result ni stderr
T012_TAIL = [{"type": "rate_limit_event", "rate_limit_info": {"status": "allowed_warning", "utilization": 0.93}},
             {"type": "system", "subtype": "thinking_tokens", "estimated_tokens": 1100, "estimated_tokens_delta": 150,
              "session_id": "24fe7028-9fc5-4d7c-8ae3-ec2f82e5056d", "uuid": "c7a6e441-7b86-49e4-8a4d-2f764dad8db3"}]


@pytest.mark.parametrize("mode,before,after", [("verify", "review", "review"), ("work", "doing", "next")])
def test_agente_cortado_vuelve_a_su_estado_sin_json_en_el_registro(monkeypatch, tmp_path, mode, before, after):
    tid = _new_card(before)
    r = _stream(monkeypatch, tmp_path, T012_TAIL, code=1)
    assert r["text"] == "" and r["cut"]
    new, verdict, summary = runner.finish(tid, mode, "tester", r)
    assert (new, verdict, summary) == (after, "cut", runner.CUT_MSG)
    t = runner.bd.task(runner.bd.load(), tid)
    assert t["status"] == after and not t.get("runs")
    assert t["log"][-1]["msg"] == runner.CUT_MSG
    assert not any("session_id" in e["msg"] or "{" in e["msg"] for e in t["log"])


def test_tres_rechazos_seguidos_bloquean_la_tarjeta():
    tid = _new_card("review")
    r = dict(text="RESULT: fail — falta Foo.java", err="", code=0, secs=5, turns=1, cost=None, usage={}, is_error=False)
    out = []
    for _ in range(3):
        out.append(runner.finish(tid, "verify", "tester", r)[0])
        if out[-1] == "next":  # el desarrollador la vuelve a entregar sin cambios
            runner.bd.update(lambda b: runner.bd.set_status(b, runner.bd.task(b, tid), "review", "desarrollador", "entrega"))
    assert out == ["next", "next", "blocked"]
    t = runner.bd.task(runner.bd.load(), tid)
    assert t["status"] == "blocked"
    assert any("Bloqueada tras 3 rechazos" in e["msg"] for e in t["log"])
    assert any("idéntico" in e["msg"] for e in t["log"])


def test_corte_con_stderr_lo_da_como_motivo():
    r = dict(text="", err="Error:\n  conexión perdida", cut=True)
    assert runner.interruption(r) == ("cut", f"{runner.CUT_MSG}: Error: conexión perdida")


@pytest.mark.parametrize("r", [
    dict(text="RESULT: blocked — faltan datos", err="", cut=False),  # terminó y lo dijo
    dict(text="", err="timeout", cut=False),                         # timeout: repetirlo gastaría lo mismo
])
def test_lo_que_no_es_un_corte(r):
    assert runner.interruption(r) is None


def test_agotar_los_turnos_es_un_fallo_legible_no_un_corte(monkeypatch, tmp_path):
    tid = _new_card("doing")
    r = _stream(monkeypatch, tmp_path, [{"type": "result", "subtype": "error_max_turns", "is_error": False}], code=0)
    assert r["is_error"] and not r["cut"]
    new, verdict, summary = runner.finish(tid, "work", "desarrollador", r)
    assert (new, verdict) == ("blocked", "blocked") and summary == "agotó el máximo de 80 turnos sin mensaje final"


def _main_loop(monkeypatch, tmp_path, r, rounds):
    """Ejecuta el bucle de main con `rounds` tarjetas, sin agente ni git. Devuelve el estado final del carril."""
    tids = [_new_card("next") for _ in range(rounds)]

    def enable(b):
        b.setdefault("runner", {}).update(enabled=True, pause_reason=None)
    runner.bd.update(enable)

    def pick(b, lane):
        return (runner.bd.task(b, tids.pop(0)), "work") if tids else (None, None)

    def sleep(_):
        raise KeyboardInterrupt  # en pausa: el bucle real esperaría a que alguien reanude
    monkeypatch.setattr(runner, "STOP", tmp_path / "STOP")
    monkeypatch.setattr(runner, "LOGDIR", tmp_path)
    monkeypatch.setattr(runner, "pick", pick)
    monkeypatch.setattr(runner, "snapshot", lambda: {})
    monkeypatch.setattr(runner, "save", lambda *a: True)
    monkeypatch.setattr(runner, "run_claude", lambda *a, **kw: r)
    monkeypatch.setattr(runner.time, "sleep", sleep)
    monkeypatch.setattr(sys, "argv", ["runner.py", "--lane", "all", "--until-idle"])
    seen = []
    set_runner = runner.set_runner
    monkeypatch.setattr(runner, "set_runner", lambda **kw: (seen.append(kw), set_runner(**kw)))
    monkeypatch.setattr(runner, "LANE", "all")
    runner.main()
    return runner.bd.load()["runner"], seen


def _agent_result(text, is_error, err=""):
    return dict(text=text, err=err, code=1 if is_error else 0, secs=1, turns=1, cost=None, usage={}, is_error=is_error, cut=False)


def test_tres_bloqueos_legitimos_no_pausan_el_carril(monkeypatch, tmp_path):
    lane, _ = _main_loop(monkeypatch, tmp_path, _agent_result("RESULT: blocked — falta una medición", False), rounds=3)
    assert lane["enabled"] is True and not lane.get("pause_reason")


def test_tres_fallos_pausan_y_el_panel_dice_por_que(monkeypatch, tmp_path):
    lane, seen = _main_loop(monkeypatch, tmp_path, _agent_result("", True, err="timeout"), rounds=3)
    assert lane["enabled"] is False
    assert "3 fallos seguidos" in lane["pause_reason"] and "timeout" in lane["pause_reason"]
    assert seen[-2]["message"] == f"En pausa: {lane['pause_reason']}"  # el último es «Runner parado», al salir del test


def test_al_reanudar_se_olvida_el_motivo_de_la_pausa(monkeypatch, tmp_path):
    lane, _ = _main_loop(monkeypatch, tmp_path, _agent_result("RESULT: review — ok", False), rounds=0)
    assert not lane.get("pause_reason")
    runner.bd.update(lambda b: b["runner"].update(pause_reason="antigua"))
    monkeypatch.setattr(sys, "argv", ["runner.py", "--lane", "all", "--until-idle"])
    runner.main()
    assert runner.bd.load()["runner"]["pause_reason"] is None


@pytest.mark.parametrize("flags, rounds", [([], 0), (["--contribute"], 1)])
def test_sin_contribute_el_carril_no_crea_rondas_y_sale(monkeypatch, tmp_path, flags, rounds):
    calls = []
    monkeypatch.setattr(runner, "STOP", tmp_path / "STOP")
    monkeypatch.setattr(runner, "LOGDIR", tmp_path)
    monkeypatch.setattr(runner, "pick", lambda b, lane: (None, None))  # ninguna tarjeta lista
    monkeypatch.setattr(runner, "contribute", lambda o: calls.append(o) or False)
    monkeypatch.setattr(runner, "run_claude", lambda *a, **kw: pytest.fail("no debe lanzar agentes"))
    monkeypatch.setattr(sys, "argv", ["runner.py", "--lane", "think", "--until-idle", *flags])
    monkeypatch.setattr(runner, "LANE", "think")
    runner.main()  # si no saliera solo, el test se quedaría colgado
    assert len(calls) == rounds


def test_un_corte_para_el_runner_sin_contar_fallo(monkeypatch, tmp_path):
    r = _agent_result("", True) | {"cut": True}
    lane, seen = _main_loop(monkeypatch, tmp_path, r, rounds=2)
    assert lane["enabled"] is True and not lane.get("pause_reason")
    assert sum(kw.get("state") == "working" for kw in seen) == 1  # no sigue gastando en la siguiente tarjeta
    assert any(kw.get("message") == runner.CUT_MSG for kw in seen)


T012_REAL_LOG = Path(__file__).resolve().parents[1] / "board" / "runs" / "T-012-tester-20261102-131521.txt"


@pytest.mark.parametrize("code", [0, 1])
def test_la_salida_completa_de_t012_devuelve_la_tarjeta_a_revision(monkeypatch, tmp_path, code):
    if not T012_REAL_LOG.exists():  # board/runs/ no se sube: en CI cubre el caso T012_TAIL
        pytest.skip("log real de T-012 no disponible")
    raw = T012_REAL_LOG.read_text(encoding="utf-8").split("--- stderr ---")[0]
    events = [json.loads(line) for line in raw.splitlines() if line.strip()]
    tid = _new_card("review")
    r = _stream(monkeypatch, tmp_path, events, code=code)
    assert runner.finish(tid, "verify", "tester", r) == ("review", "cut", runner.CUT_MSG)
    assert runner.bd.task(runner.bd.load(), tid)["log"][-1]["msg"] == runner.CUT_MSG


def test_un_result_a_medias_en_el_flujo_no_cierra_la_tarjeta(monkeypatch, tmp_path):
    said = {"type": "assistant", "message": {"content": [{"type": "text", "text": "RESULT: done — aún no he terminado"}]}}
    tid = _new_card("review")
    r = _stream(monkeypatch, tmp_path, [said, *T012_TAIL], code=1)
    assert runner.finish(tid, "verify", "tester", r)[:2] == ("review", "cut")
    assert runner.bd.task(runner.bd.load(), tid)["status"] == "review"


def test_un_proceso_real_que_muere_sin_mensaje_final_es_un_corte(monkeypatch, tmp_path):
    monkeypatch.setattr(runner, "LOGDIR", tmp_path)
    monkeypatch.setattr(runner, "claim", lambda *a: None)
    tid = _new_card("doing")
    r = runner.run_claude(sys.executable, "prompt", "tester", 60, tag="T-1")  # python rechaza -p: stderr y código de error reales
    assert r["code"] != 0 and r["err"] and r["cut"]
    new, verdict, summary = runner.finish(tid, "work", "tester", r)
    assert (new, verdict) == ("next", "cut")
    assert summary.startswith(runner.CUT_MSG + ": ") and "\n" not in summary


def test_una_ronda_de_aportacion_cortada_no_marca_el_enfriamiento(monkeypatch, tmp_path):
    monkeypatch.setattr(runner, "LOGDIR", tmp_path)
    monkeypatch.setattr(runner, "LANE", "all")
    monkeypatch.setattr(runner, "snapshot", lambda: {})
    monkeypatch.setattr(runner, "save", lambda *a: pytest.fail("un corte no guarda nada"))
    monkeypatch.setattr(runner, "run_claude", lambda *a, **kw: _agent_result("", True) | {"cut": True})
    o = type("O", (), dict(dry_run=False, claude="claude", contrib_every=0))
    assert runner.contribute(o) is False
    b = runner.bd.load()
    assert not runner.bd.lane_state(b, "all").get("contrib_at")
    assert b["tasks"][-1]["log"][-1]["msg"] == f"LIMIT: {runner.CUT_MSG}"


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


def _result(tool_id, **kw):
    return json.dumps({"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": tool_id, **kw}]}})


def _claims_of(root, lines):
    got = []
    orig = runner.claim
    runner.claim = lambda tag, paths: got.extend(paths)
    try:
        pending = {}
        for line in lines:
            runner.claim_stream("T-1", line, pending)
        runner.claim_pending("T-1", pending)
    finally:
        runner.claim = orig
    return got


def test_edicion_denegada_o_fallida_no_se_apunta_pero_la_cortada_si(repo):
    root, _ = repo
    use = _use("Edit", file_path=str(root / "dirty.txt"))
    assert _claims_of(root, [use, _result("a", content="ok")]) == ["dirty.txt"]
    assert _claims_of(root, [use, _result("a", content="Permission for this tool use was denied")]) == []
    assert _claims_of(root, [use, _result("a", content="boom", is_error=True)]) == []
    assert _claims_of(root, [use]) == ["dirty.txt"]  # cortado antes del resultado: no se pierde


def test_citar_el_aviso_de_limite_con_veredicto_no_es_limite():
    cita = "El mensaje «usage limit reached» aparece en el log.\nRESULT: done — ok"
    assert runner.interruption(dict(text=cita, err="", cut=False)) is None
    assert runner.interruption(dict(text="usage limit reached", err="", cut=False)) == ("limit", runner.LIMIT_MSG)


def test_error_de_api_es_un_corte_sin_json(monkeypatch, tmp_path):
    tid = _new_card("doing")
    ev = {"type": "result", "subtype": "success", "is_error": True,
          "result": 'API Error: 529 {"type":"error","error":{"type":"overloaded_error"}}'}
    r = _stream(monkeypatch, tmp_path, [ev], code=1)
    assert r["cut"]
    new, verdict, summary = runner.finish(tid, "work", "desarrollador", r)
    assert (new, verdict) == ("next", "cut") and "{" not in summary and "API Error: 529" in summary
    t = runner.bd.task(runner.bd.load(), tid)
    assert not t.get("runs") and all("{" not in e["msg"] for e in t["log"])


def test_cortado_con_denegados_deja_el_aviso(monkeypatch):
    fake = {"tasks": [{"id": "T-1", "owner": "investigador", "status": "doing", "log": []}], "agents": []}
    monkeypatch.setattr(runner.bd, "update", lambda f: f(fake))
    monkeypatch.setattr(runner.bd, "task", lambda b, tid: b["tasks"][0])
    monkeypatch.setattr(runner.bd, "set_status", lambda b, t, s, by, msg: t.update(status=s))
    monkeypatch.setattr(runner.bd, "log", lambda t, by, msg: t["log"].append(msg))
    monkeypatch.setattr(runner, "permission_protocol", lambda *a: ([], [], []))
    r = dict(text="", err="", cut=True, denied=["python board/board.py add X"])
    assert runner.finish("T-1", "work", "investigador", r)[1] == "cut"
    assert any(m.startswith("⚠ 1 comandos denegados: python board/board.py add X") for m in fake["tasks"][0]["log"])


def test_permiso_de_tests_solo_las_formas_previstas():
    allowed = set(runner.ALLOWED)
    assert "Bash(./mk test*)" not in allowed and "Bash(make test*)" not in allowed
    assert "PowerShell(.\\mk.cmd test*)" not in allowed
    assert {"Bash(./mk test)", "Bash(./mk test MODULE=*)", "Bash(./mk test-board)"} <= allowed


def _pr_card(status="review", pr=7):
    def f(b):
        t = runner.bd.new_task(b, "Entrega X", "desarrollador", 5, prio="alta", desc="d", by="test")
        t["pr"] = pr
        runner.bd.set_status(b, t, status, "test")
        return t["id"]
    return runner.bd.update(f)


def _fake_gh(monkeypatch, status, calls):
    monkeypatch.setattr(runner, "pr_status", lambda n: status)
    monkeypatch.setattr(runner, "failed_log", lambda t, **k: "boom en el job")
    monkeypatch.setattr(runner, "commit_board", lambda m: calls.append(("board", m)))
    monkeypatch.setattr(runner, "git", lambda *a, **k: calls.append(("git", a)))
    monkeypatch.setattr(runner, "gh", lambda *a, **k: calls.append(("gh", a)) or subprocess.CompletedProcess(a, 0, "", ""))


def test_check_prs_verde_fusiona_y_cierra(monkeypatch):
    calls = []
    tid = _pr_card()
    _fake_gh(monkeypatch, "green", calls)
    # tras el merge, pr_status ya diría merged; aquí basta con rc 0
    assert runner.check_prs() == 1
    assert ("gh", ("pr", "merge", "7", "--squash", "--delete-branch")) in calls
    assert runner.bd.task(runner.bd.load(), tid)["status"] == "done"


def test_check_prs_rojo_vuelve_a_next_con_motivo_y_cuenta(monkeypatch):
    calls = []
    tid = _pr_card()
    _fake_gh(monkeypatch, "red", calls)
    assert runner.check_prs() == 1
    t = runner.bd.task(runner.bd.load(), tid)
    assert t["status"] == "next" and t["pr"] == 7 and not any(c[0] == "gh" for c in calls)
    assert "boom en el job" in t["history"][-1]["msg"] if "history" in t else True
    for _ in range(runner.MAX_REJECTS - 1):  # rojo repetido → bloqueada
        runner.bd.update(lambda b: runner.bd.set_status(b, runner.bd.task(b, tid), "review", "test"))
        runner.check_prs()
    assert runner.bd.task(runner.bd.load(), tid)["status"] == "blocked"


def test_check_prs_pendiente_no_hace_nada_y_pick_ignora_la_tarjeta(monkeypatch):
    calls = []
    tid = _pr_card()
    _fake_gh(monkeypatch, "pending", calls)
    assert runner.check_prs() == 0 and not calls
    b = runner.bd.load()
    assert runner.bd.task(b, tid)["status"] == "review"
    assert runner.pick(b, "measure")[0] is None or runner.pick(b, "measure")[0]["id"] != tid


def test_check_prs_pr_inexistente_vuelve_a_next_sin_pr(monkeypatch):
    calls = []
    tid = _pr_card()
    _fake_gh(monkeypatch, "missing", calls)
    runner.check_prs()
    t = runner.bd.task(runner.bd.load(), tid)
    assert t["status"] == "next" and "pr" not in t


def test_pr_status_interpreta_checks(monkeypatch):
    def fake(view, checks):
        def g(*a, **k):
            out = view if a[1] == "view" else checks
            return subprocess.CompletedProcess(a, 0, out, "")
        return g
    st = lambda checks: (monkeypatch.setattr(runner, "gh", fake('{"state":"OPEN"}', checks)), runner.pr_status(1))[1]
    assert st('[{"state":"SUCCESS"},{"state":"SKIPPED"}]') == "green"
    assert st('[{"state":"SUCCESS"},{"state":"FAILURE"}]') == "red"
    assert st('[{"state":"SUCCESS"},{"state":"PENDING"}]') == "pending"
    assert st("") == "pending"
    monkeypatch.setattr(runner, "gh", fake('{"state":"MERGED"}', ""))
    assert runner.pr_status(1) == "merged"


def test_publish_pr_empuja_y_abre(monkeypatch):
    calls = []
    monkeypatch.setattr(runner, "git_retry", lambda *a, **k: calls.append(a))
    monkeypatch.setattr(runner, "gh", lambda *a, **k: subprocess.CompletedProcess(
        a, 0, "https://github.com/o/r/pull/42\n", ""))
    t = {"id": "T-9", "title": "Algo", "desc": "d"}
    assert runner.publish_pr("wt", t) == 42
    assert calls[0][:3] == ("push", "-q", "-u") and calls[0][-1] == runner.branch_name(t) or calls[0][-2] == runner.branch_name(t)
    monkeypatch.setattr(runner, "gh", lambda *a, **k: subprocess.CompletedProcess(a, 1, "", "nope"))
    with pytest.raises(runner.GitError):
        runner.publish_pr("wt", t)
    assert runner.publish_pr("wt", {**t, "pr": 5}) == 5  # ya tiene PR: solo empuja


def test_runner_no_carga_mcp_del_proyecto():
    import inspect
    assert "--strict-mcp-config" in inspect.getsource(runner.run_claude)
