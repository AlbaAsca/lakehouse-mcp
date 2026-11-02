"""Protocolo de permisos: lo seguro se concede solo, lo prohibido nunca, lo dudoso lo valida una persona."""
import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "board"))
import board as bd  # noqa: E402
import permissions as pm  # noqa: E402
import runner  # noqa: E402


def test_clasifica_los_comandos_que_se_denegaron_de_verdad():
    assert pm.classify("git show 98cb191 -- board/board.py")[:2] == ("seguro", "git show*")
    assert pm.classify(".venv/Scripts/python board/board.py list")[0] == "seguro"
    assert pm.classify("git tag --list base-v*")[1] == "git tag --list*"
    assert pm.classify("ZERO_N=28 python scripts/run.py")[0] == "encadenado"
    assert pm.classify("cat >> tests/x.py <<'EOF'")[0] == "encadenado"
    assert pm.classify('python board/board.py add "x" --desc "a\nb"')[0] == "encadenado"
    assert pm.classify("git push origin main")[0] == "prohibido"
    assert pm.classify("kaggle competitions submit -c x")[0] == "prohibido"
    assert pm.classify("python -m pip install x")[0] == "prohibido"
    assert pm.classify("./mvnw -q verify")[0] == "seguro"
    assert pm.classify("./mk test")[0] == "seguro"
    assert pm.classify("./mk chaos")[0] == "revisar"
    assert pm.classify("docker compose logs --tail 50 kafka")[0] == "seguro"
    assert pm.classify("docker compose down -v")[0] == "revisar"
    assert pm.classify("Rscript foo.R")[:2] == ("revisar", "Rscript *")


@pytest.mark.parametrize("cmd", ["./mk test MODULE=producer", "mk.cmd test MODULE=mcp-server", ".\\mk.cmd test MODULE=agent",
                                 "make test MODULE=testkit"])
def test_admite_los_tests_de_un_modulo(cmd):
    assert pm.classify(cmd)[0] == "seguro"


@pytest.mark.parametrize("cmd", ["./mk test MODULE=", "./mk test MODULE=../evil", "./mk test MODULE=producer up", "./mk chaos MODULE=producer",
                                 "./mk test-board MODULE=producer", "./mk test MODULE=producer MAVEN_ARGS=-X",
                                 "make test MODULE=producer,agent", "make down MODULE=producer"])
def test_module_no_abre_otros_objetivos_ni_argumentos(cmd):
    assert pm.classify(cmd)[0] == "revisar"


@pytest.mark.skipif(not shutil.which("make"), reason="make solo existe en la imagen tools")
def test_makefile_con_module_no_ejecuta_los_tests_de_las_dependencias():
    def recetas(*args):
        r = subprocess.run(["make", "--no-print-directory", "-n", "test", *args], cwd=ROOT, capture_output=True, text=True, check=True)
        return r.stdout.splitlines()

    assert recetas() == ["mvn -B -q verify"]
    assert recetas("MODULE=") == ["mvn -B -q verify"]
    assert recetas("MODULE=producer") == ["mvn -B -q -pl producer -am -DskipTests install", "mvn -B -q -pl producer verify"]


def tarea(b):
    t = bd.new_task(b, "Algo", "desarrollador", 10)
    t["status"] = "blocked"
    return t


def test_protocolo_concede_lo_seguro_y_pide_lo_dudoso():
    b = {"tasks": [], "agents": []}
    t = tarea(b)
    text = "trabajo…\nPERMISO: Rscript foo.R — comparar con el paquete forecast de R\nRESULT: blocked — falta R"
    notes, granted, requests = runner.permission_protocol(b, t, ["git show abc", "Rscript foo.R", "git push"], text, "desarrollador")
    assert granted and any("concedido" in n for n in notes) and any("✗" in n for n in notes)
    assert [x["pattern"] for x in pm.load()["allowed"]] == ["git show*"]          # registrado (en la copia del test)
    req = bd.task(b, requests[0])
    assert req["status"] == "human" and req["perm"]["pattern"] == "Rscript *"
    assert "paquete forecast" in req["perm"]["why"]
    # una segunda denegación del mismo patrón no abre otra solicitud
    _, _, again = runner.permission_protocol(b, t, ["Rscript bar.R"], "", "desarrollador")
    assert again == requests and len([x for x in b["tasks"] if x.get("kind") == "permiso"]) == 1


def test_botones_conceder_y_denegar(tmp_path):
    import importlib
    d = tmp_path / "board"
    shutil.copytree(ROOT / "board", d, ignore=shutil.ignore_patterns("runs", "__pycache__", ".lock", "permissions.json"))
    sys.path.insert(0, str(d))
    try:
        import server
        importlib.reload(server)
        assert Path(server.__file__).parent == d
        from fastapi.testclient import TestClient
        c = TestClient(server.app)
        orig = c.post("/api/board/task", json={"title": "Tarea", "owner": "desarrollador"}).json()["id"]
        req = c.post("/api/board/task", json={"title": "Solicitud de permiso: Rscript *", "owner": "consultor",
                                               "status": "human"}).json()["id"]
        bj = json.loads((d / "board.json").read_text(encoding="utf-8"))
        next(x for x in bj["tasks"] if x["id"] == req)["perm"] = {"pattern": "Rscript *", "task": orig, "agent": "desarrollador"}
        (d / "board.json").write_text(json.dumps(bj, ensure_ascii=False), encoding="utf-8")
        assert c.post(f"/api/permissions/{req}", json={"decision": "grant"}).json()["ok"]
        assert json.loads((d / "permissions.json").read_text(encoding="utf-8"))["allowed"][0]["pattern"] == "Rscript *"
        assert c.post(f"/api/permissions/{req}", json={"decision": "deny"}).status_code == 409   # ya resuelta
        assert c.post(f"/api/permissions/{orig}", json={"decision": "grant"}).status_code == 409  # no es solicitud
    finally:
        sys.path.remove(str(d))
