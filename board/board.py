"""Panel de trabajo: board.json + CLI. Todas las escrituras pasan por update() (con bloqueo).

Tiempos: las estimaciones son en MINUTOS DE AGENTE (estimate_min). El tiempo real (actual_min)
no se escribe a mano: se calcula de started_at/finished_at, que se ponen solos al mover la tarea.

CLI:
  python board/board.py list [--owner X]
  python board/board.py add "Título" --owner desarrollador --est 15 [--prio alta] [--desc ".."] [--manual "qué debe hacer el responsable"] [--deps T-001,T-002]
  python board/board.py move T-012 doing [--by desarrollador] [--msg ".."]
  python board/board.py note T-012 "texto" [--by X]
  python board/board.py desc T-012 "texto"   (descripción: lenguaje llano arriba; datos técnicos bajo una línea «## Técnico»)
  python board/board.py estimate T-012 20
  python board/board.py check
"""
import argparse
import json
import os
import sys
import time
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path

F = Path(__file__).with_name("board.json")
LOCK = Path(__file__).with_name(".lock")
STATES = ["backlog", "next", "doing", "review", "human", "blocked", "done"]
COLUMNS = [("backlog", "Backlog"), ("next", "Próximo"), ("doing", "En curso"), ("review", "Revisión"),
           ("human", "Te necesita"), ("blocked", "Bloqueado"), ("done", "Hecho")]
PRIO = {"alta": 0, "media": 1, "baja": 2}


def now():
    return datetime.now().astimezone().isoformat(timespec="seconds")


def parse(t):
    return datetime.fromisoformat(t) if t else None


@contextmanager
def _lock(timeout=10):
    t0 = time.time()
    while True:
        try:
            fd = os.open(LOCK, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            break
        except FileExistsError:
            if time.time() - LOCK.stat().st_mtime > 30:  # bloqueo huérfano
                LOCK.unlink(missing_ok=True)
                continue
            if time.time() - t0 > timeout:
                raise TimeoutError("board bloqueado")
            time.sleep(0.05)
    try:
        yield
    finally:
        os.close(fd)
        LOCK.unlink(missing_ok=True)


def load():
    return json.loads(F.read_text(encoding="utf-8"))


def _write(b):
    b["updated"] = now()
    validate(b)
    tmp = F.with_suffix(".tmp")
    tmp.write_text(json.dumps(b, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(tmp, F)


def update(fn):
    """Lee, aplica fn(board) y guarda de forma atómica y con bloqueo. Devuelve lo que devuelva fn."""
    with _lock():
        b = load()
        r = fn(b)
        _write(b)
        return r


def validate(b):
    ids = {a["id"] for a in b["agents"]}
    seen = set()
    for t in b["tasks"]:
        assert t["id"] not in seen, f"id duplicado {t['id']}"
        seen.add(t["id"])
        assert t["status"] in STATES, f"{t['id']}: estado {t['status']}"
        assert t["owner"] in ids, f"{t['id']}: owner {t['owner']}"
        assert isinstance(t.get("estimate_min") or 0, (int, float)), f"{t['id']}: estimate_min"
    return True


# Carril «pensar»: roles que leen y escriben documentos y tarjetas. Cualquier otro rol (desarrollador, tester, los
# que añadas) va a «medir», que es el que usa CPU para tests y mediciones. Ajusta THINK_ROLES a tus agentes.
THINK_ROLES = {"investigador", "experto", "arquitecto"}
LANE_OWNERS = {"think": THINK_ROLES,
               "measure": {"desarrollador", "tester"}}
LANES = ("think", "measure")


def lane_state(b, lane):
    """Estado del runner de un carril: 'all' = clave histórica `runner`; think/measure = `runners.<carril>`."""
    if lane == "all":
        return b.setdefault("runner", {})
    return b.setdefault("runners", {}).setdefault(lane, {})


def lane_owns(lane, owner):
    return lane == "all" or (owner in THINK_ROLES) == (lane == "think")


def pid_alive(pid):
    """¿Sigue vivo el proceso? (sin os.kill: en Windows os.kill mata el proceso)."""
    if not pid:
        return False
    if os.name != "nt":
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False
    import ctypes
    k = ctypes.windll.kernel32
    h = k.OpenProcess(0x1000, False, int(pid))  # PROCESS_QUERY_LIMITED_INFORMATION
    if not h:
        return False
    code = ctypes.c_ulong()
    ok = k.GetExitCodeProcess(h, ctypes.byref(code))
    k.CloseHandle(h)
    return bool(ok) and code.value == 259  # STILL_ACTIVE



def _unescape(text):
    """«\\n» escrito en una línea → salto de línea: el runner deniega comandos de varias líneas."""
    return text.replace("\\n", "\n") if text else text


def task(b, tid):
    return next(t for t in b["tasks"] if t["id"] == tid)


def log(t, by, msg):
    t.setdefault("log", []).append({"t": now(), "by": by, "msg": msg})


def set_status(b, t, status, by="consultor", msg=None):
    """Cambia estado y gestiona tiempos y agentes automáticamente."""
    prev = t["status"]
    t["status"], t["updated"] = status, now()
    if status == "doing" and prev != "doing":
        t["started_at"], t["finished_at"] = now(), None
    if prev == "doing" and status != "doing" and t.get("started_at"):
        t["finished_at"] = now()
        run_min = (parse(t["finished_at"]) - parse(t["started_at"])).total_seconds() / 60
        t["actual_min"] = round((t.get("actual_min") or 0) + run_min, 1)  # acumula si vuelve a doing
    log(t, by, msg or f"→ {dict(COLUMNS)[status]}")
    for a in b["agents"]:
        if status == "doing" and a["id"] == t["owner"]:
            a.update(status="working", current=t["id"])
        elif a.get("current") == t["id"] and status != "doing":
            a.update(status="idle", current=None)


def new_task(b, title, owner, est, prio="media", desc="", status="backlog", manual=None, by="consultor"):
    n = max((int(t["id"][2:]) for t in b["tasks"]), default=0) + 1
    t = dict(id=f"T-{n:03}", title=title, owner=owner, status=status, priority=prio, estimate_min=est,
             actual_min=None, auto=manual is None, manual=manual, desc=desc, deps=[], created=now(),
             updated=now(), started_at=None, finished_at=None, log=[])
    log(t, by, "creada")
    b["tasks"].append(t)
    return t


def deps_done(b, t):
    done = {x["id"] for x in b["tasks"] if x["status"] == "done"}
    return all(d in done for d in t.get("deps", []))


def main():
    if hasattr(sys.stdout, "reconfigure"):  # consola Windows (cp1252): no romper con «≤», «→»…
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    p = argparse.ArgumentParser()
    sp = p.add_subparsers(dest="cmd", required=True)
    l = sp.add_parser("list"); l.add_argument("--owner")
    m = sp.add_parser("move"); m.add_argument("id"); m.add_argument("status", choices=STATES)
    m.add_argument("--by", default="consultor"); m.add_argument("--msg")
    a = sp.add_parser("add"); a.add_argument("title"); a.add_argument("--owner", required=True)
    a.add_argument("--est", type=float, required=True, help="minutos de agente")
    a.add_argument("--prio", default="media", choices=list(PRIO)); a.add_argument("--desc", default="")
    a.add_argument("--status", default="backlog", choices=STATES); a.add_argument("--manual")
    a.add_argument("--by", default="consultor"); a.add_argument("--deps", default="", help="T-001,T-002")
    n = sp.add_parser("note"); n.add_argument("id"); n.add_argument("text", nargs="?"); n.add_argument("--msg")
    n.add_argument("--by", default="consultor")
    d = sp.add_parser("desc"); d.add_argument("id"); d.add_argument("text"); d.add_argument("--by", default="consultor")
    e = sp.add_parser("estimate"); e.add_argument("id"); e.add_argument("minutes", type=float)
    sp.add_parser("check")
    o = p.parse_args()

    if o.cmd == "check":
        validate(load()); print("OK"); return
    if o.cmd == "list":
        for t in load()["tasks"]:
            if not o.owner or t["owner"] == o.owner:
                real = f"{t['actual_min']:.0f}" if t.get("actual_min") is not None else "–"
                print(f"{t['id']:6} {t['status']:8} {t['owner']:13} est {t.get('estimate_min') or '?':>4} min · real {real:>4}  "
                      f"{'' if t.get('auto', True) else '[Tú] '}{t['title']}")
        return
    if o.cmd == "add":
        def f(b):
            t = new_task(b, o.title, o.owner, o.est, o.prio, _unescape(o.desc), o.status, o.manual, o.by)
            t["deps"] = [d.strip() for d in o.deps.split(",") if d.strip()]
            return t["id"]
        print(update(f))
        return
    if o.cmd == "move":
        update(lambda b: set_status(b, task(b, o.id), o.status, o.by, o.msg)); return
    if o.cmd == "note":
        text = o.text or o.msg
        if not text:
            p.error("note necesita el texto (posicional o --msg)")
        update(lambda b: log(task(b, o.id), o.by, text)); return
    if o.cmd == "desc":
        def g(b):
            t = task(b, o.id); t["desc"], t["updated"] = _unescape(o.text), now(); log(t, o.by, "descripción reescrita")
        update(g); return
    if o.cmd == "estimate":
        def f(b):
            t = task(b, o.id); t["estimate_min"] = o.minutes; log(t, "consultor", f"estimación {o.minutes:g} min")
        update(f)


if __name__ == "__main__":
    sys.exit(main())
