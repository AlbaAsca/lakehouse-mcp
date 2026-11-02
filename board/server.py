"""Servidor del panel: http://localhost:8020/  (FastAPI, sin dependencias del proyecto).

Uso: python board/server.py [--port 8020]
Puede convivir con el servidor de tu proyecto (otro puerto).
"""
import argparse
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, Response

HERE = Path(__file__).parent
BOARD = HERE / "board.json"
app = FastAPI(title="Panel de agentes")


def _bd():
    spec = importlib.util.spec_from_file_location("board", HERE / "board.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


@app.get("/")
def panel():
    return FileResponse(HERE / "panel.html", headers={"Cache-Control": "no-store"})


LANES = ("think", "measure")  # carriles del runner (board.py: LANES); el estado histórico `runner` = carril «all»


def _lanes(lane):
    if lane is None:
        return list(LANES)
    if lane not in LANES and lane != "all":
        raise HTTPException(400, "lane debe ser think, measure o all")
    return [lane]


def _state(b, lane):
    return b.setdefault("runner", {}) if lane == "all" else b.setdefault("runners", {}).setdefault(lane, {})


AGENTS_DIR = HERE.parent / ".claude" / "agents"


def agent_config(aid):
    """model/effort del frontmatter de .claude/agents/<id>.md (se lee en cada petición: siempre al día)."""
    f = AGENTS_DIR / f"{aid}.md"
    if not f.is_file():
        return {}
    m = re.match(r"---\s*\n(.*?)\n---", f.read_text(encoding="utf-8"), re.S)
    return dict(re.findall(r"^(model|effort):\s*(\S+)", m.group(1), re.M)) if m else {}


@app.get("/api/board")
def get_board():
    b = json.loads(BOARD.read_text(encoding="utf-8"))
    for a in b.get("agents", []):
        a.update(agent_config(a["id"]))
    runners = b.setdefault("runners", {})
    for r in [b.setdefault("runner", {})] + [runners.setdefault(k, {}) for k in LANES]:
        r["alive"] = pid_alive(r.get("pid"))
        if not r["alive"] and r.get("state") in ("working", "idle"):
            r.update(state="stopped", current=None)  # pid muerto: no mostrar carriles fantasma
        live = HERE / "runs" / r["live"] if r.get("live") else None
        r["last_activity"] = (datetime.fromtimestamp(live.stat().st_mtime).astimezone().isoformat(timespec="seconds")
                              if live and live.is_file() else None)  # última línea que escribió el agente
    return Response(json.dumps(b, ensure_ascii=False), media_type="application/json", headers={"Cache-Control": "no-store"})


EDITABLE = {"title", "owner", "priority", "estimate_min", "desc", "deps", "manual"}


@app.patch("/api/board/task/{tid}")
async def patch_task(tid: str, request: Request):
    """Edita una tarea. El estado pasa por set_status para que los tiempos reales se registren solos."""
    body, bd = await request.json(), _bd()

    def f(b):
        t = bd.task(b, tid)
        changed = [k for k in EDITABLE if k in body and body[k] != t.get(k)]
        for k in changed:
            t[k] = body[k]
        if "manual" in changed:
            t["auto"] = not body["manual"]
        if changed:
            bd.log(t, "tú", "editada: " + ", ".join(changed))
        if body.get("status") and body["status"] != t["status"]:
            bd.set_status(b, t, body["status"], "tú")
        if body.get("note"):
            bd.log(t, "tú", body["note"])
    try:
        bd.update(f)
    except (StopIteration, AssertionError) as e:
        raise HTTPException(400, str(e) or "tarea no encontrada")
    return {"ok": True}


@app.delete("/api/board/task/{tid}")
def delete_task(tid: str):
    bd = _bd()

    def f(b):
        n = len(b["tasks"])
        b["tasks"] = [t for t in b["tasks"] if t["id"] != tid]
        if len(b["tasks"]) == n:
            raise HTTPException(404, "tarea no encontrada")
    bd.update(f)
    return {"ok": True}


@app.post("/api/board/task")
async def create_task(request: Request):
    body, bd = await request.json(), _bd()
    try:
        t = bd.update(lambda b: bd.new_task(b, body["title"], body["owner"], body.get("estimate_min") or 10,
                                            body.get("priority", "media"), body.get("desc", ""),
                                            body.get("status", "backlog"), body.get("manual") or None, "tú"))
    except (KeyError, AssertionError) as e:
        raise HTTPException(400, str(e))
    return {"id": t["id"]}


@app.post("/api/board/runner")
async def runner_toggle(request: Request):
    enabled, bd = bool((await request.json()).get("enabled")), _bd()

    def f(b):
        b.setdefault("runner", {})["enabled"] = enabled
    bd.update(f)
    return {"enabled": enabled}


# ---- Control del runner desde el panel (Windows) ----
ROOT = HERE.parent
TASK_NAME = f"{ROOT.name} runner"  # tarea del Programador de tareas (scripts/install-schedule.ps1)
_sched_cache = {"t": 0, "v": None}


def pid_alive(pid):
    return _bd().pid_alive(pid)


def claude_bin():
    found = shutil.which("claude")
    if found:
        return found
    hits = sorted(Path(os.environ.get("APPDATA", "")).glob("Claude/claude-code/*/*/claude.exe"), key=lambda p: p.stat().st_mtime)
    return str(hits[-1]) if hits else "claude"


@app.get("/api/runner/schedule")
def runner_schedule():
    """Próxima y última ejecución automática (Programador de tareas). Cacheado 60 s."""
    if time.time() - _sched_cache["t"] < 60 and _sched_cache["v"]:
        return _sched_cache["v"]
    v = {"task": TASK_NAME, "exists": False}
    if os.name == "nt":
        ps = (f"$t=Get-ScheduledTask -TaskName '{TASK_NAME}' -ErrorAction SilentlyContinue; if($t){{$i=$t|Get-ScheduledTaskInfo;"
              "@{state=[string]$t.State;next=$i.NextRunTime.ToString('s');last=$i.LastRunTime.ToString('s');"
              "result=$i.LastTaskResult}|ConvertTo-Json -Compress}")
        try:
            out = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True,
                                 timeout=20, creationflags=0x08000000).stdout.strip()
            if out:
                v.update(json.loads(out), exists=True)
        except (subprocess.SubprocessError, json.JSONDecodeError, OSError) as e:
            v["error"] = str(e)
    _sched_cache.update(t=time.time(), v=v)
    return v


def _short(x, n=220):
    x = " ".join(str(x).split())
    return x if len(x) <= n else x[:n] + "…"


def live_events(lines):
    """Resume los eventos stream-json de la CLI en líneas legibles: qué piensa, qué herramienta usa y qué le devuelve.
    Cada línea lleva su hora («t», ISO) y, si una herramienta sigue en marcha, una última línea «espera» con lo que lleva."""
    from datetime import datetime, timedelta

    out, t_last, started, done, progress = [], None, {}, set(), None

    def add(kind, text, t=None):
        out.append({"kind": kind, "text": text, "t": t or t_last})

    for line in lines:
        try:
            e = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(e, dict):
            continue
        typ = e.get("type")
        t_last = e.get("timestamp") or t_last
        if typ == "system" and e.get("subtype") == "init":
            add("inicio", f"modelo {e.get('model', '?')}")
        elif typ == "tool_progress":  # latido de una herramienta larga (cada 30 s)
            progress = (e.get("parent_tool_use_id"), e.get("tool_name"), e.get("elapsed_time_seconds") or 0)
        elif typ in ("assistant", "user"):
            for c in (e.get("message") or {}).get("content") or []:
                if not isinstance(c, dict):
                    continue
                if c.get("type") == "text" and c.get("text", "").strip():
                    add("texto", _short(c["text"], 600))
                elif c.get("type") == "thinking" and c.get("thinking", "").strip():
                    add("piensa", _short(c["thinking"], 600))
                elif c.get("type") == "tool_use":
                    i = c.get("input") or {}
                    arg = i.get("command") or i.get("file_path") or i.get("pattern") or i.get("query") or i.get("url") or i.get("description") or ""
                    started[c.get("id")] = (t_last, c.get("name"), _short(arg, 120))
                    add("herramienta", f"{c.get('name')}: {_short(arg, 300)}")
                elif c.get("type") == "tool_result":
                    done.add(c.get("tool_use_id"))
                    body = c.get("content")
                    if isinstance(body, list):
                        body = " ".join(x.get("text", "") for x in body if isinstance(x, dict))
                    kind = "resultado"
                    if c.get("is_error"):  # distinguir «me lo han prohibido» de un fallo normal que el agente corrige solo
                        kind = "denegado" if "Permission for this tool use was denied" in (body or "") else "error"
                    add(kind, _short(body or "(vacío)"))
        elif typ == "result":
            add("fin", _short(e.get("result") or e.get("subtype") or "", 400))
    if progress and progress[0] not in done and progress[0] in started:
        t0, name, arg = started[progress[0]]
        secs = int(progress[2])
        try:
            t = (datetime.fromisoformat(t0.replace("Z", "+00:00")) + timedelta(seconds=secs)).isoformat() if t0 else None
        except ValueError:
            t = None
        add("espera", f"{name} sigue ejecutándose: lleva {secs // 60} min {secs % 60:02d} s · {arg}", t)
    return out


@app.get("/api/runner/live")
def runner_live(n: int = 60, lane: str = "all"):
    """Lo que está haciendo el agente ahora (o hizo en la última ejecución), sin gastar tokens: lee el log en vivo."""
    runs = HERE / "runs"
    _lanes(lane)
    name = _state(json.loads(BOARD.read_text(encoding="utf-8")), lane).get("live")
    f = runs / name if name and (runs / name).is_file() else None
    if not f and lane == "all":
        files = sorted(runs.glob("live-*.jsonl"), key=lambda x: x.stat().st_mtime) if runs.is_dir() else []
        f = files[-1] if files else None
    if not f:
        return {"file": None, "events": []}
    lines = f.read_text(encoding="utf-8", errors="replace").splitlines()[-400:]
    return {"file": f.name, "events": live_events(lines)[-n:]}


@app.post("/api/runner/start")
def runner_start(lane: str = None):
    """Arranca el runner en segundo plano: trabaja tarea tras tarea y se para cuando no quedan (--until-idle).
    Sin ?lane= arranca los dos carriles (think con --contribute, measure); los que ya estén vivos se respetan."""
    lanes, b = _lanes(lane), json.loads(BOARD.read_text(encoding="utf-8"))
    legacy = pid_alive(b.get("runner", {}).get("pid"))  # un runner «all» ya cubre todos los carriles
    todo = [] if legacy else [k for k in lanes if not pid_alive(_state(b, k).get("pid"))]
    if not todo:
        raise HTTPException(409, "El runner ya está en marcha")
    bd = _bd()

    def en(x):
        x.setdefault("runner", {}).update(enabled=True)
        for k in todo:
            _state(x, k).update(enabled=True)
    bd.update(en)
    env = dict(os.environ, PYTHONUTF8="1", CLAUDE_BIN=claude_bin())
    venv = ROOT / ".venv" / ("Scripts" if os.name == "nt" else "bin")
    env["PATH"] = f"{venv}{os.pathsep}{env.get('PATH', '')}"
    (HERE / "runs").mkdir(exist_ok=True)
    log = open(HERE / "runs" / "panel.log", "a", encoding="utf-8")
    flags = 0x00000008 | 0x00000200 | 0x08000000 if os.name == "nt" else 0  # DETACHED | NEW_GROUP | NO_WINDOW
    args = dict(cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    pids = {}
    for k in todo:
        cmd = [sys.executable, str(HERE / "runner.py"), "--lane", k, "--until-idle"] + (["--contribute"] if k != "measure" else [])
        try:  # fuera del "job" del proceso padre: si no, Windows lo mata cuando el padre termina
            p = subprocess.Popen(cmd, creationflags=flags | (0x01000000 if os.name == "nt" else 0), **args)
        except OSError:
            p = subprocess.Popen(cmd, creationflags=flags, **args)
        pids[k] = p.pid
    return {"ok": True, "pids": pids, "pid": next(iter(pids.values()))}


@app.post("/api/runner/stop")
def runner_stop(lane: str = None):
    """Para el runner (o un carril) y al agente que esté trabajando (mata el árbol de procesos). La tarea vuelve a Próximo."""
    bd = _bd()
    lanes = _lanes(lane) if lane else [*LANES, "all"]  # sin lane = los dos carriles y el runner único histórico
    killed = False
    for k in lanes:
        pid = _state(bd.load(), k).get("pid")
        if pid_alive(pid):
            killed = True
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True, creationflags=0x08000000)
            else:
                os.kill(pid, 15)

    def f(b):
        for k in lanes:
            for t in b["tasks"]:
                if t["status"] != "doing" or not bd.lane_owns(k, t["owner"]):
                    continue
                if t.get("kind") == "contrib":
                    bd.set_status(b, t, "done", "tú", "ronda parada desde el panel")
                elif t["owner"] != "consultor":
                    bd.set_status(b, t, "next", "tú", "parada desde el panel; se reintentará")
            for a in b["agents"]:
                if a["id"] != "consultor" and bd.lane_owns(k, a["id"]):
                    a.update(status="idle", current=None)
            _state(b, k).update(state="stopped", current=None, message="Parado desde el panel", heartbeat=bd.now())
    bd.update(f)
    return {"ok": True, "killed": killed}


@app.post("/api/permissions/{tid}")
async def permission_decision(tid: str, request: Request):
    """Botones Conceder / Denegar de una «Solicitud de permiso»: lo apunta en permissions.json y libera la tarjeta que esperaba."""
    body, bd = await request.json(), _bd()
    decision = body.get("decision")
    if decision not in ("grant", "deny"):
        raise HTTPException(400, "decision debe ser grant o deny")
    spec = importlib.util.spec_from_file_location("permissions", HERE / "permissions.py")
    pm = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(pm)

    def f(b):
        t = bd.task(b, tid)
        perm = t.get("perm")
        if not perm or t["status"] == "done":
            raise HTTPException(409, "Esta tarjeta no es una solicitud de permiso pendiente")
        why = body.get("why") or ("concedido" if decision == "grant" else "denegado") + " por el responsable desde el panel"
        pm.record("allowed" if decision == "grant" else "denied", perm["pattern"], why, "responsable")
        bd.set_status(b, t, "done", "tú", ("✓ Concedido: " if decision == "grant" else "✗ Denegado: ") + perm["pattern"])
        try:
            orig = bd.task(b, perm["task"])
            bd.log(orig, "tú", ("✓ permiso concedido: " if decision == "grant" else "✗ permiso denegado (no lo intentes otra vez): ")
                   + perm["pattern"])
        except StopIteration:
            pass
    try:
        bd.update(f)
    except StopIteration:
        raise HTTPException(404, "tarjeta no encontrada")
    return {"ok": True}


VERSION_TAGS = re.compile(os.environ.get("BOARD_VERSION_TAGS", r"^v?\d+(\.\d+)*$"))  # qué tags de git cuentan como versión


@app.get("/api/versions")
def versions():
    """Versiones del proyecto (tags de git, por fecha; patrón en BOARD_VERSION_TAGS) para agrupar lo hecho. Si existen eval/kaggle.json y
    eval/results/<versión>.json, añade la nota de Kaggle y si la versión se retiró."""
    try:
        out = subprocess.run(["git", "for-each-ref", "refs/tags", "--sort=creatordate",
                              "--format=%(refname:short)|%(creatordate:iso-strict)"], cwd=ROOT, capture_output=True,
                             text=True, creationflags=0x08000000 if os.name == "nt" else 0).stdout
    except OSError:
        out = ""
    tags = [ln.split("|", 1) for ln in out.splitlines() if "|" in ln]
    tags = [(n, d) for n, d in tags if VERSION_TAGS.match(n)]
    kag = {}
    kf = ROOT / "eval" / "kaggle.json"
    if kf.exists():
        for e in json.loads(kf.read_text(encoding="utf-8")).get("submissions", []):
            kag[e.get("base")] = e
    res = []
    for name, date in tags:
        info = kag.get(name, {})
        rf = ROOT / "eval" / "results" / f"{name}.json"
        retired = rf.exists() and json.loads(rf.read_text(encoding="utf-8")).get("active", True) is False
        res.append({"name": name, "date": date, "tag": info.get("tag"), "lb": info.get("lb"), "retired": retired})
    return res


if __name__ == "__main__":
    import uvicorn
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8020)
    uvicorn.run(app, host="127.0.0.1", port=ap.parse_args().port)
