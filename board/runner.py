"""Runner autónomo: coge tareas del panel y las ejecuta con Claude Code (`claude -p --agent <rol>`).

Orden: 1) tareas en Revisión → las verifica el tester; 2) Próximo; 3) Backlog (por prioridad).
Salta: Te necesita, Bloqueado, tareas del consultor (son tuyas) y dependencias sin terminar.
Mide tiempo real, turnos y coste de cada ejecución, y hace commit git antes y después.
Se pausa desde el panel (botón Pausar) o creando el archivo board/STOP.

Con --contribute, si no hay tareas ejecutables, un agente (por turnos: investigador, experto, arquitecto) revisa
el estado del proyecto y aporta algo pequeño o registra tarjetas; como mucho una vez cada --contrib-every horas.

Dos carriles en paralelo (--lane): think = investigador, experto, arquitecto (board.THINK_ROLES; + rondas de aportación);
measure = el resto (desarrollador, tester…) (las verificaciones de review siempre van a measure); all (por defecto) = todo en uno.
Cada carril tiene su estado en board.json (`runners.<carril>`; all usa `runner`) y su guarda de instancia única.
Los commits de cada tarjeta incluyen lo que escribió su agente (board/runs/claims/), lo que cambió durante ella sin ser de otra tarjeta y board/board.json, nunca `git add -A`.

Uso: python board/runner.py [--lane think|measure|all] [--once | --until-idle] [--contribute] [--contrib-every H] [--dry-run] [--claude RUTA]
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import board as bd  # noqa: E402
import permissions as pm  # noqa: E402

ROOT = Path(__file__).parents[1]
STOP = Path(__file__).with_name("STOP")
LOGDIR = Path(__file__).with_name("runs")
CLAIMS = LOGDIR / "claims"  # <tarjeta>.txt: ficheros que ha escrito su agente y aún no están en un commit
EDIT_TOOLS = {"Write", "Edit", "NotebookEdit"}
RESULT_RE = re.compile(r"RESULT:\s*(done|review|human|blocked|fail)\b\s*[—:-]?\s*(.*)", re.I)
MAX_FAILS = 3

# Permisos "código y tests": edita archivos, ejecuta python/pytest/git local. Nada de instalar, encadenar ni push.
# python = el del proyecto (.venv si existe: run-agents*.bat lo pone primero en PATH).
ALLOWED = ["Read", "Edit", "Write", "Glob", "Grep", "WebSearch", "WebFetch",
           "Bash(python *)", "Bash(git status*)", "Bash(git diff*)", "Bash(git log*)",
           "Bash(ls*)", "Bash(cat *)", "Bash(head *)", "Bash(tail *)", "Bash(wc *)", "Bash(mkdir *)", "Bash(diff *)",
           # en Windows la CLI puede usar PowerShell en vez de Bash: mismos permisos
           "Bash(./mvnw *)", "Bash(./mk test*)", "Bash(./mk evals*)", "Bash(make test*)", "Bash(make evals*)",
           "Bash(docker compose ps*)", "Bash(docker compose logs*)",
           "PowerShell(.\\mvnw.cmd *)", "PowerShell(.\\mk.cmd test*)", "PowerShell(.\\mk.cmd evals*)",
           "PowerShell(docker compose ps*)", "PowerShell(docker compose logs*)",
           "PowerShell(python *)", "PowerShell(git status*)", "PowerShell(git diff*)", "PowerShell(git log*)",
           "PowerShell(Get-ChildItem*)", "PowerShell(Get-Content*)"]
# Añade aquí las herramientas de solo lectura de tu proyecto (p. ej. una CLI externa sin permisos de escritura).
DENIED = ["Bash(python -m pip *)", "Bash(pip *)", "Bash(npm *)", "Bash(npx *)", "Bash(winget *)", "Bash(choco *)",
          "Bash(curl *)", "Bash(wget *)", "Bash(cp *)", "Bash(mv *)", "Bash(rm *)",
          "Bash(git add*)", "Bash(git commit*)", "Bash(git push*)", "Bash(git reset*)", "Bash(git checkout*)",
          "PowerShell(python -m pip *)", "PowerShell(pip *)", "PowerShell(Copy-Item*)", "PowerShell(Remove-Item*)",
          "PowerShell(git add*)", "PowerShell(git commit*)", "PowerShell(git push*)"]

RULES = """Reglas (modo autónomo, permisos: solo código y tests):
- Nadie va a responder preguntas: decide tú dentro de tu rol, con el criterio más sensato.
- Sigue CLAUDE.md. Trabaja SOLO en esta tarea. Si ves otras mejoras o bugs, regístralos con
  `python board/board.py add "Título" --owner <rol> --est <minutos de agente> --prio <alta|media|baja> --desc "..." --by {owner}`.
  Las estimaciones son en MINUTOS DE AGENTE (lo que tardarías tú), no horas humanas.
- Cómo escribir la descripción de una tarjeta (la lee una persona, no un robot):
  arriba, 2-4 frases en lenguaje llano: qué problema hay, qué idea probamos y por qué; después una línea
  «Cómo sabremos si funciona: …» en palabras. Los datos técnicos (fuentes, nombres de variables, fórmulas, números,
  criterio exacto) van al final, debajo de una línea que diga exactamente «## Técnico». Sin siglas ni nombres de código en la parte de arriba.
- NO edites board/board.json a mano: usa siempre board/board.py.
- No hagas git add ni git commit (está bloqueado): el runner guarda tus cambios al terminar la tarjeta.
- Si te deniegan un comando que necesitas de verdad, no busques un rodeo que haga lo mismo: sigue con lo que puedas y añade
  antes de RESULT una línea `PERMISO: <comando exacto> — <para qué lo necesitas>`. El runner lo concede si es de solo lectura,
  o abre una solicitud que valida el consultor o el responsable; tu tarjeta se reintenta cuando se resuelve.
- Variables de entorno: no escribas `VAR=x python …` (se deniega); pásalas como argumento del script.
- No uses `python -c` de varias líneas, `cat >>` ni heredoc (se deniegan): crea o edita el fichero con Write/Edit
  (por ejemplo un script en scripts/analysis/) y ejecútalo con `python scripts/analysis/<script>.py`.
- Comandos permitidos: python (scripts, pytest, board.py), git status/diff/log, ls/cat/head/tail/diff y mkdir.
  Lanza cada comando solo (sin `cd ... &&`, `;` ni `|`), o se denegará. Para copiar o crear ficheros usa Edit/Write.
- Los comandos de board.py van solos, nunca después de cat, heredoc, && o ;: si no, se deniegan y la tarjeta no se crea.
  Escribe el comando en UNA sola línea: para separar párrafos usa «\n» dentro de --desc (board.py lo convierte en salto de línea). Un comando con saltos de línea reales se deniega y la tarjeta no se crea.
- NO instales software ni dependencias, no descargues binarios, no hagas git push. Si la tarea lo
  necesita, o necesita un archivo o una decisión del responsable, para y termina con RESULT: human explicando
  exactamente qué tiene que hacer ella (pasos concretos).
- Si tocas código: pasa solo los tests de tu módulo y déjalos en verde; añade tests nuevos si procede. La suite completa la pasa la CI; las excepciones están en CLAUDE.md (Tests por niveles).
- Responde en español. Tu ÚLTIMA línea debe ser exactamente:
  RESULT: <estado> — <resumen en una frase>
  {states}"""

STATES_DEV = "Estados: review (hecho, falta verificar), human (necesita al responsable), blocked (no puedes avanzar)."
STATES_DOC = "Estados: done (terminado), human (necesita al responsable), blocked (no puedes avanzar)."
STATES_QA = ("Estados: done (verificado: has leído el diff, pasado los tests y probado el comportamiento), "
             "fail (no pasa: explica cómo reproducirlo), blocked (no se puede verificar aún, p. ej. falta algo externo: "
             "NUNCA human por esto), human (solo si necesita algo que únicamente puede hacer el responsable).")


CONTRIB_ROLES = ["investigador", "experto", "arquitecto"]
CONTRIB = {
    "investigador": "Busca una alternativa, librería o técnica concreta con ganancia esperada que no esté ya en el panel.",
    "experto": "Revisa lo último que se ha cerrado (git log, board/runs/) y señala riesgos o deuda que un senior no dejaría pasar.",
    "arquitecto": "Revisa que el panel refleje el plan (CLAUDE.md): dependencias, tareas grandes sin trocear, huecos, "
                  "y que README.md esté al día con lo cerrado.",
}
CONTRIB_RULES = """Eres el agente «{agent}» y hoy no hay tareas asignadas para ti. Ronda de aportación (máx. 15 min de agente):
1. Ponte al día: CLAUDE.md, `python board/board.py list` y docs/.
2. Dentro de tu rol: {focus}
3. Solo si aporta de verdad: registra como mucho 2 tarjetas con
   `python board/board.py add "Título" --owner <rol> --est <min> --prio <alta|media|baja> --desc "<lenguaje llano; datos técnicos debajo de ## Técnico>" --by {agent}`
   y/o deja el detalle en docs/NOTAS-{agent}.md (fecha + hallazgo + fuente). No dupliques tarjetas ni notas existentes.
4. No cambies código: eso va por tarjeta.
- NO edites board/board.json a mano. NO instales nada ni hagas git push. Responde en español.
Tu ÚLTIMA línea debe ser exactamente: RESULT: done — <qué aportaste> | RESULT: none — nada útil que aportar ahora"""


def now():
    return datetime.now().astimezone().isoformat(timespec="seconds")


LANE = "all"  # carril de esta instancia (se fija en main)


def set_runner(**kw):
    def f(b):
        bd.lane_state(b, LANE).update(kw, heartbeat=now())
    bd.update(f)


def clean_dead_lanes(b):
    """Carriles (y el runner sin carril) con pid muerto o sin pid no pueden estar 'working': pasan a stopped."""
    for st in [b.setdefault("runner", {})] + [b.setdefault("runners", {}).setdefault(k, {}) for k in bd.LANES]:
        if st.get("state") in (None, "stopped") and not st.get("pid"):
            continue
        if not bd.pid_alive(st.get("pid")) and st.get("pid") != os.getpid():
            st.update(state="stopped", current=None, pid=None, message="Runner parado (pid muerto)")


def enabled():
    if STOP.exists():
        return False
    b = bd.load()
    return b.get("runner", {}).get("enabled", True) and bd.lane_state(b, LANE).get("enabled", True)


def other_instance(b, lane):
    """pid vivo de otra instancia que choca con este carril (el mismo carril, o all vs. cualquiera), o None."""
    keys = ("all", *bd.LANES) if lane == "all" else (lane, "all")
    for k in keys:
        pid = (b.get("runner", {}) if k == "all" else b.get("runners", {}).get(k, {})).get("pid")
        if pid and pid != os.getpid() and bd.pid_alive(pid):
            return pid
    return None


def pick(b, lane="all"):
    """Siguiente tarea ejecutable: (tarea, modo) con modo 'verify' o 'work'. Las verificaciones van al carril measure."""
    # tareas que necesitan algo del responsable (manual) solo corren cuando el responsable las mueve a Próximo
    ok = lambda t: t["owner"] != "consultor" and t.get("kind") != "contrib" and bd.deps_done(b, t) and (t.get("auto", True) or t["status"] == "next")
    key = lambda t: (bd.PRIO.get(t.get("priority"), 1), t["id"])
    rev = sorted([t for t in b["tasks"] if t["status"] == "review" and ok(t)], key=key) if lane in ("all", "measure") else []
    if rev:
        return rev[0], "verify"
    for st in ("next", "backlog"):
        c = sorted([t for t in b["tasks"] if t["status"] == st and ok(t) and bd.lane_owns(lane, t["owner"])], key=key)
        if c:
            return c[0], "work"
    return None, None


def build_prompt(t, mode, agent):
    hist = "\n".join(f"- {l['t'][:16]} {l['by']}: {l['msg']}" for l in t.get("log", [])[-8:])
    if mode == "verify":
        head = (f"Eres el TESTER. Verifica la tarea {t['id']} «{t['title']}» que el {t['owner']} ha dejado en Revisión.\n"
                "Revisa el cambio (git log/diff), pasa los tests del módulo, prueba el comportamiento y añade un test que cubra la tarea si no existe. Mira también la última CI de develop (CLAUDE.md, Tests por niveles).")
        states = STATES_QA
    else:
        head = f"Eres el agente «{agent}». Ejecuta la tarea {t['id']}: {t['title']}"
        states = STATES_DEV if agent == "desarrollador" else STATES_DOC
    return (f"{head}\n\nDescripción: {t.get('desc') or '(sin descripción)'}\n"
            f"Estimación: {t.get('estimate_min') or '?'} min\nHistorial reciente:\n{hist}\n\n"
            + RULES.format(owner=agent, states=states))


def git(*args):
    if not (ROOT / ".git").exists() or not shutil.which("git"):
        return None
    return subprocess.run(["git", *args],  # identidad: la configurada en el repo
                          cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")


class GitError(RuntimeError):
    pass


def git_retry(*args, tries=5):
    """Como git(), pero reintenta (p. ej. index.lock del otro carril) y lanza GitError si sigue fallando: un fallo mudo pierde ficheros."""
    for i in range(tries):
        r = git(*args)
        if r is None or r.returncode == 0:
            return r
        if i < tries - 1:
            time.sleep(1)
    raise GitError(f"git {args[0]}: {(r.stderr or r.stdout).strip()[:300]}")


def snapshot():
    """Estado del árbol de trabajo: {ruta: (estado git, mtime, tamaño)}. Sirve para saber qué cambió durante una tarjeta."""
    r = git_retry("status", "--porcelain", "-z", "-uall")
    out, parts = {}, (r.stdout.split("\0") if r else [])
    i = 0
    while i < len(parts):
        e = parts[i]
        i += 1
        if len(e) < 4:
            continue
        if e[0] in "RC":
            i += 1  # el siguiente campo es la ruta de origen del renombrado
        p = e[3:]
        f = ROOT / p
        st = f.stat() if f.is_file() else None
        out[p] = (e[:2], st.st_mtime_ns if st else None, st.st_size if st else None)
    return out


def tool_paths(line):
    """Rutas del repo que una línea del stream del agente va a escribir (Write/Edit); lo de fuera del repo no cuenta."""
    if "tool_use" not in line:
        return []
    try:
        content = json.loads(line)["message"]["content"]
    except (json.JSONDecodeError, KeyError, TypeError):
        return []
    out = []
    for c in content if isinstance(content, list) else []:
        fp = (c.get("input") or {}).get("file_path") if isinstance(c, dict) and c.get("name") in EDIT_TOOLS else None
        if fp:
            try:
                out.append((ROOT / fp).resolve().relative_to(ROOT.resolve()).as_posix())
            except ValueError:
                pass
    return out


def claim(tid, paths):
    """Apunta los ficheros de la tarjeta al momento: sobrevive a un corte y el otro carril sabe que no son suyos."""
    if not paths:
        return
    try:
        CLAIMS.mkdir(parents=True, exist_ok=True)
        with (CLAIMS / f"{tid}.txt").open("a", encoding="utf-8") as f:
            f.writelines(p + "\n" for p in paths)
    except OSError as e:  # no debe tumbar al agente en marcha
        print(f"  ⚠ no se pudo apuntar {paths} en {tid}: {e}")


def claims():
    """{tarjeta: ficheros apuntados} de las tarjetas aún sin guardar."""
    return {f.stem: set(f.read_text(encoding="utf-8").splitlines()) for f in CLAIMS.glob("*.txt")}


def commit_own(before, msg, tid=None):
    """Commit con los ficheros apuntados por la tarjeta `tid` (aunque sean de un intento anterior o ya estuvieran en `before`),
    lo que cambió desde `before` (ficheros generados por comandos, que nadie apunta) y board/board.json.
    Lo apuntado por otra tarjeta y lo que ya estaba a medias no entran. Lanza GitError si no se puede guardar; lo apuntado se conserva."""
    apuntado = claims()
    own = apuntado.pop(tid, set())
    foreign = set().union(*apuntado.values())
    after = snapshot()
    wanted = ({p for p, v in after.items() if before.get(p) != v} - foreign) | own
    # solo lo que git admite: una ruta ignorada o que ya no existe haría fallar el add y el commit enteros
    known = git_retry("ls-files", "-c", "-o", "--exclude-standard", "-z", "--", *wanted) if wanted else None
    files = sorted({p for p in (known.stdout.split("\0") if known else []) if p} | {"board/board.json"})
    git_retry("add", "--", *files)
    pending = git("diff", "--cached", "--quiet", "--", *files)
    if pending and pending.returncode:  # sin cambios, commit devolvería error
        git_retry("commit", "-q", "-m", msg, "--", *files)
    if tid:
        (CLAIMS / f"{tid}.txt").unlink(missing_ok=True)
    return files


def save(before, tid, msg):
    """Guarda y sube la tarjeta. Si git falla lo deja anotado en ella: sus ficheros siguen apuntados y entran en su siguiente commit."""
    try:
        commit_own(before, msg, tid)
    except GitError as e:
        print(f"  ⚠ {tid} sin guardar: {e}")
        bd.update(lambda b: bd.log(bd.task(b, tid), "runner", f"⚠ commit fallido, ficheros sin guardar: {e}"))
        return False
    git("push", "-q", "--follow-tags")  # sincroniza con GitHub (lo hace el runner, nunca el agente)
    return True


# Tope de turnos por rol (medido en un proyecto real: los agentes que investigan gastaban 3-4 veces más por ejecución)
MAX_TURNS = {"experto": 40, "investigador": 25, "tester": 40}


def run_claude(claude, prompt, agent, timeout_s, tag="run", max_turns=80):
    """Lanza al agente con salida en streaming: cada evento (texto, herramienta, resultado) se guarda al momento en
    board/runs/live-<tag>-<agente>-<fecha>.jsonl para verlo en directo desde el panel. No cuesta tokens extra:
    es la misma ejecución, solo que la CLI va contando lo que hace en vez de esperar al final."""
    # --strict-mcp-config sin --mcp-config: el agente no carga ningún MCP del proyecto (p. ej. uno que publique o envíe algo fuera)
    cmd = [claude, "-p", "--agent", agent, "--output-format", "stream-json", "--verbose", "--strict-mcp-config",
           "--permission-mode", "acceptEdits", "--permission-prompts", "none",
           "--max-turns", str(max_turns), "--allowedTools", *ALLOWED, *pm.rules()[0], "--disallowedTools", *DENIED, *pm.rules()[1]]
    live = LOGDIR / f"live-{tag}-{agent}-{datetime.now():%Y%m%d-%H%M%S}.jsonl"
    set_runner(live=live.name)
    errf = live.with_suffix(".err")
    t0, data, lines, killed = time.time(), {}, [], []
    try:
        with errf.open("w", encoding="utf-8") as ef:  # stderr a fichero: un pipe lleno bloquearía al agente
            p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=ef,
                                 text=True, encoding="utf-8", errors="replace", cwd=ROOT)
    except OSError as e:
        return dict(text="", err=str(e), code=-1, secs=0, turns=None, cost=None, usage={}, is_error=True)
    timer = threading.Timer(timeout_s, lambda: (killed.append(1), p.kill()))
    timer.start()
    try:
        p.stdin.write(prompt)
        p.stdin.close()
        with live.open("w", encoding="utf-8") as f:
            for line in p.stdout:
                f.write(line)
                f.flush()
                lines.append(line)
                claim(tag, tool_paths(line))
                if '"type":"result"' in line.replace(" ", ""):
                    try:
                        data = json.loads(line)
                    except json.JSONDecodeError:
                        pass
        code = p.wait()
    finally:
        timer.cancel()
    err = errf.read_text(encoding="utf-8", errors="replace")
    errf.unlink(missing_ok=True)
    if killed:
        err, code = "timeout", -1
    text = data.get("result") if isinstance(data.get("result"), str) else "".join(lines[-20:])
    return dict(denied=denied_commands(lines), text=text or "", err=err[-2000:] if err else "", code=code, secs=time.time() - t0,
                turns=data.get("num_turns"), cost=data.get("total_cost_usd"),
                usage=data.get("usage") or {}, is_error=data.get("is_error", code != 0))


DENIED_MARK = "Permission for this tool use was denied"


def denied_commands(lines):
    """Comandos (o herramientas) cuyo tool_result del live-*.jsonl dice que el permiso fue denegado."""
    uses, out = {}, []
    for line in lines:
        try:
            ev = json.loads(line)
        except (json.JSONDecodeError, TypeError):
            continue
        msg = ev.get("message") if isinstance(ev, dict) else None
        content = msg.get("content") if isinstance(msg, dict) else None
        if not isinstance(content, list):
            continue
        for c in content:
            if not isinstance(c, dict):
                continue
            if c.get("type") == "tool_use":
                inp = c.get("input") or {}
                uses[c.get("id")] = str(inp.get("command") or inp.get("file_path") or c.get("name") or "")
            elif c.get("type") == "tool_result" and DENIED_MARK in json.dumps(c.get("content"), ensure_ascii=False):
                out.append(uses.get(c.get("tool_use_id"), "?"))
    return out


def read_denied(path):
    """Lo mismo, leyendo un fichero live-*.jsonl."""
    return denied_commands(Path(path).read_text(encoding="utf-8", errors="replace").splitlines())


PERM_RE = re.compile(r"PERMISO:\s*(.+?)\s+[—-]\s+(.+)")


def permission_protocol(b, t, denied, text, agent):
    """Protocolo de permisos (board/permissions.py) para los comandos que le denegaron al agente.
    Devuelve (notas para la tarjeta, ¿se concedió algo?, ids de solicitudes abiertas)."""
    reasons = {m.group(1).strip(): m.group(2).strip() for m in PERM_RE.finditer(text or "")}
    notes, granted, requests = [], False, []
    for cmd in dict.fromkeys(denied):  # sin repetidos, en orden
        short = " ".join(cmd.split())[:90]
        cat, pat, why = pm.classify(cmd)
        if cat == "seguro":
            pm.record("allowed", pat, f"concedido automáticamente a {agent} ({t['id']}): {why}", "runner")
            notes.append(f"✓ permiso concedido automáticamente: {pat} ({why}); se reintenta")
            granted = True
        elif cat in ("prohibido", "encadenado"):
            notes.append(f"✗ «{short}»: {why}")
        else:
            pending = next((x for x in b["tasks"] if x.get("perm", {}).get("pattern") == pat and x["status"] != "done"), None)
            if pending is None:
                agent_why = next((v for k, v in reasons.items() if k in cmd or cmd.startswith(k)), "")
                pending = bd.new_task(
                    b, f"Solicitud de permiso: {pat}", "consultor", 2, "media",
                    f"El agente «{agent}» necesitaba un comando que no está en su lista de permitidos y se lo bloquearon "
                    f"mientras hacía {t['id']}. No es de solo lectura ni está prohibido, así que lo tiene que validar una "
                    f"persona.\n\nPara qué lo quería: {agent_why or '(no lo explicó)'}\n\nCómo decidir: concede si es seguro "
                    f"repetirlo sin supervisión dentro del proyecto; deniega si escribe fuera, publica, borra o usa la red.\n\n"
                    f"## Técnico\n- Comando: {short}\n- Patrón que se concedería: {pat}\n- Tarjeta: {t['id']}",
                    status="human", manual="Abre la tarjeta y pulsa «Conceder» o «Denegar».", by="runner")
                pending["kind"] = "permiso"
                pending["perm"] = {"pattern": pat, "command": short, "agent": agent, "task": t["id"], "why": agent_why}
            requests.append(pending["id"])
            notes.append(f"? permiso solicitado: {pat} ({pending['id']}); la tarjeta espera la decisión")
    return notes, granted, requests



LIMIT_RE = re.compile(r"you.?ve hit your (weekly |daily |monthly |usage |session |5-hour )?limit|usage limit reached", re.I)
LIMIT_MSG = "límite de uso, reintenta tras el reset"


def is_usage_limit(r):
    """La salida de Claude indica que se agotó el límite de uso (no es un fallo de la tarea)."""
    return bool(LIMIT_RE.search(f"{r.get('text') or ''}\n{r.get('err') or ''}"))


def finish(tid, mode, agent, r):
    if is_usage_limit(r):  # devuelve la tarea a su estado previo; ni blocked ni fallo
        def back(b):
            t = bd.task(b, tid)
            new = "review" if mode == "verify" else "next"
            bd.set_status(b, t, new, agent, LIMIT_MSG)
            for a in b["agents"]:
                if a["id"] == agent and a.get("current") == tid:
                    a.update(status="idle", current=None)
            return new
        return bd.update(back), "limit", LIMIT_MSG
    m = None
    for line in reversed(r["text"].strip().splitlines()):
        m = RESULT_RE.search(line)
        if m:
            break
    verdict = m.group(1).lower() if m else ("blocked" if r["is_error"] else "review")
    summary = (m.group(2).strip() if m else (r["err"] or r["text"][-300:]).strip())[:400] or "sin resumen"
    denied = r.get("denied") or []
    if any("board.py add" in d for d in denied) and verdict in ("done", "review"):
        verdict = "blocked"  # las tarjetas que debía crear no existen
    warn = (f"⚠ {len(denied)} comandos denegados: " + " | ".join(" ".join(d.split())[:80] for d in denied)) if denied else None
    u = r["usage"]
    toks = (u.get("input_tokens") or 0) + (u.get("output_tokens") or 0) + (u.get("cache_read_input_tokens") or 0) \
        + (u.get("cache_creation_input_tokens") or 0)

    def f(b):
        t = bd.task(b, tid)
        if mode == "verify":
            new = {"done": "done", "fail": "next", "human": "human"}.get(verdict, "blocked")
            if new == "next":
                t["owner"] = "desarrollador"
        else:
            new = {"done": "done", "review": "review", "human": "human"}.get(verdict, "blocked")
            if new == "done" and agent == "desarrollador":
                new = "review"  # el código siempre lo verifica el tester
        runs = t.setdefault("runs", [])
        runs.append(dict(agent=agent, mode=mode, at=now(), min=round(r["secs"] / 60, 1), turns=r["turns"],
                         tokens=toks or None, cost_usd=r["cost"], verdict=verdict))
        if verdict == "human" and summary:
            t["manual"], t["auto"] = summary, False
        bd.set_status(b, t, new, agent, f"{verdict.upper()}: {summary}")
        if warn:
            bd.log(t, agent, warn)
        if denied:
            notes, granted, requests = permission_protocol(b, t, denied, r.get("text"), agent)
            for n in notes:
                bd.log(t, "runner", n)
            if requests:  # espera a la decisión: vuelve a la cola cuando se resuelvan las solicitudes
                t["deps"] = sorted(set(t.get("deps") or []) | set(requests))
            if (granted or requests) and new == "blocked":
                new = "review" if mode == "verify" else "next"
                bd.set_status(b, t, new, "runner", "se reintentará con los permisos resueltos")
        return new
    return bd.update(f), verdict, summary


def contribute(o):
    """Ronda de aportación cuando no hay tareas: un rol por vez, con enfriamiento."""
    st = bd.lane_state(bd.load(), LANE)
    last = st.get("contrib_at")
    if last and (datetime.now().astimezone() - datetime.fromisoformat(last)).total_seconds() < o.contrib_every * 3600:
        return False
    agent = CONTRIB_ROLES[(CONTRIB_ROLES.index(st["contrib_agent"]) + 1) % len(CONTRIB_ROLES)
                          if st.get("contrib_agent") in CONTRIB_ROLES else 0]
    print(f"\n[{now()[11:19]}] aportación · {agent}")
    prompt = CONTRIB_RULES.format(agent=agent, focus=CONTRIB[agent])
    if o.dry_run:
        print(prompt); return True
    def card(b):  # visible en el panel mientras trabaja (CLAUDE.md: el panel es la fuente de verdad)
        t = bd.new_task(b, f"Ronda de aportación: {agent}", agent, 15, prio="baja", desc=CONTRIB[agent], by="runner")
        t["kind"] = "contrib"
        bd.set_status(b, t, "doing", "runner", "empieza la ronda")
        return t["id"]
    tid = bd.update(card)
    before = snapshot()
    set_runner(state="working", current=tid, agent=agent, started=now(), message=f"{agent}: ronda de aportación ({tid})")
    r = run_claude(o.claude, prompt, agent, timeout_s=20 * 60, tag=tid, max_turns=min(25, MAX_TURNS.get(agent, 80)))
    (LOGDIR / f"contrib-{agent}-{datetime.now():%Y%m%d-%H%M%S}.txt").write_text(
        f"{r['text']}\n\n--- stderr ---\n{r['err']}", encoding="utf-8")
    m = next((RESULT_RE.search(l) or re.search(r"RESULT:\s*(none)\b\s*[—:-]?\s*(.*)", l, re.I)
              for l in reversed(r["text"].strip().splitlines())
              if "RESULT:" in l), None)
    summary = (m.group(2).strip() if m else "sin RESULT")[:300]
    verdict = (m.group(1).lower() if m else "blocked")
    if is_usage_limit(r):
        summary, verdict = LIMIT_MSG, "limit"
    u = r["usage"]
    toks = sum(u.get(k) or 0 for k in ("input_tokens", "output_tokens", "cache_read_input_tokens",
                                       "cache_creation_input_tokens")) or None

    def close(b):
        t = bd.task(b, tid)
        t.setdefault("runs", []).append(dict(agent=agent, mode="contrib", at=now(), min=round(r["secs"] / 60, 1),
                                            turns=r["turns"], tokens=toks, cost_usd=r["cost"], verdict=verdict))
        bd.set_status(b, t, "blocked" if verdict == "blocked" else "done", agent, f"{verdict.upper()}: {summary}")
    bd.update(close)
    if verdict == "limit":
        set_runner(contrib_at=None, state="idle", current=None, message=LIMIT_MSG)
        return False
    set_runner(contrib_at=now(), contrib_agent=agent, state="idle", current=None,
               message=f"aportación {agent} ({tid}): {summary}")
    save(before, tid, f"chore(runner): contribution round by {agent}")
    print(f"  → {summary}")
    return True


def main():
    sys.stdout.reconfigure(line_buffering=True, encoding="utf-8", errors="replace")  # cp1252 en Programador de tareas
    ap = argparse.ArgumentParser()
    ap.add_argument("--lane", choices=["think", "measure", "all"], default="all",
                    help="think = board.THINK_ROLES; measure = el resto; all = todo")
    ap.add_argument("--once", action="store_true", help="ejecuta una tarea y sale")
    ap.add_argument("--dry-run", action="store_true", help="muestra qué haría sin ejecutar")
    ap.add_argument("--claude", default=os.environ.get("CLAUDE_BIN") or shutil.which("claude") or "claude")
    ap.add_argument("--idle", type=int, default=30, help="segundos de espera cuando no hay tareas")
    ap.add_argument("--contribute", action="store_true", help="sin tareas: ronda de aportación de un agente")
    ap.add_argument("--until-idle", action="store_true", help="trabaja tarea tras tarea y sale cuando no quedan")
    ap.add_argument("--contrib-every", type=float, default=6, help="horas mínimas entre rondas de aportación")
    o = ap.parse_args()
    global LANE
    LANE = o.lane
    if LANE == "measure":
        o.contribute = False  # las rondas de aportación son del carril de pensar
    LOGDIR.mkdir(exist_ok=True)
    other = other_instance(bd.load(), LANE)
    if other:  # p. ej. botón Iniciar + ejecución programada
        print(f"Ya hay un runner del carril {LANE} en marcha (pid {other}): no arranco otro.")
        return
    fails = 0
    print(f"Runner [{LANE}] en marcha · claude = {o.claude}\nPausa: botón en el panel o crea board/STOP · Ctrl+C para salir")
    bd.update(clean_dead_lanes)
    set_runner(state="idle", current=None, message="Runner en marcha", pid=os.getpid())

    def recover(b):  # tareas que se quedaron "En curso" si el runner se cortó
        for x in b["tasks"]:
            if x["status"] != "doing" or not bd.lane_owns(LANE, x["owner"]):
                continue
            if x.get("kind") == "contrib":
                bd.set_status(b, x, "done", "runner", "ronda interrumpida (runner reiniciado)")
            elif x["owner"] != "consultor":
                bd.set_status(b, x, "next", "runner", "interrumpida (runner reiniciado); se reintentará")
        for a in b["agents"]:
            if a["id"] != "consultor" and bd.lane_owns(LANE, a["id"]):
                a.update(status="idle", current=None)
    bd.update(recover)
    try:
        mtime = Path(__file__).stat().st_mtime
        while True:
            if Path(__file__).stat().st_mtime != mtime:  # código del runner actualizado: recargar entre tareas
                print("runner.py ha cambiado: recargando…")
                set_runner(state="idle", current=None, message="Recargando runner")
                os.execv(sys.executable, [sys.executable, __file__, *sys.argv[1:]])
            if not enabled():
                set_runner(state="paused", current=None, message="En pausa")
                if o.once:  # ejecución programada: no esperar a que alguien reanude
                    print("En pausa (panel o board/STOP): no se ejecuta nada.")
                    break
                time.sleep(5)
                continue
            b = bd.load()
            t, mode = pick(b, LANE)
            if not t:
                set_runner(state="idle", current=None, message="Sin tareas ejecutables")
                if o.contribute and contribute(o) and o.dry_run:
                    break
                if o.once or o.until_idle:
                    break
                time.sleep(o.idle)
                continue
            agent = "tester" if mode == "verify" else t["owner"]
            print(f"\n[{now()[11:19]}] {t['id']} · {agent} · {mode} · {t['title']}")
            if o.dry_run:
                print(build_prompt(t, mode, agent)); break
            tid = t["id"]

            def start(b):
                x = bd.task(b, tid)
                if mode == "verify":
                    bd.log(x, "tester", "verificando")
                    x["verify_started"] = now()
                    for a in b["agents"]:
                        if a["id"] == "tester":
                            a.update(status="working", current=tid)
                else:
                    bd.set_status(b, x, "doing", "runner", f"empieza {agent}")
            bd.update(start)
            before = snapshot()
            set_runner(state="working", current=tid, agent=agent, started=now(), message=f"{agent} con {tid}")

            est = t.get("estimate_min") or 15
            r = run_claude(o.claude, build_prompt(t, mode, agent), agent, timeout_s=int(min(120, max(15, est * 4)) * 60), tag=tid,
                           max_turns=MAX_TURNS.get(agent, 80))
            (LOGDIR / f"{tid}-{agent}-{datetime.now():%Y%m%d-%H%M%S}.txt").write_text(
                f"{r['text']}\n\n--- stderr ---\n{r['err']}", encoding="utf-8")
            new, verdict, summary = finish(tid, mode, agent, r)
            if mode == "verify":
                def free(b):
                    for a in b["agents"]:
                        if a["id"] == "tester" and a.get("current") == tid:
                            a.update(status="idle", current=None)
                bd.update(free)
            save(before, tid, f"chore(runner): {tid} {verdict} by {agent}")
            print(f"  → {new} en {r['secs'] / 60:.1f} min · {summary}")

            if verdict == "limit":  # sin fallo contado; parar hasta el reset (la ejecución programada reintenta)
                set_runner(state="idle", current=None, message=LIMIT_MSG)
                break
            fails = fails + 1 if (r["is_error"] or verdict == "blocked") else 0
            if fails >= MAX_FAILS:
                set_runner(state="paused", current=None, message=f"Pausado tras {MAX_FAILS} fallos seguidos; revisa board/runs/")
                bd.update(lambda b: bd.lane_state(b, LANE).update(enabled=False))
                fails = 0
            if o.once:
                break
    except KeyboardInterrupt:
        pass
    finally:
        set_runner(state="stopped", current=None, message="Runner parado")


if __name__ == "__main__":
    main()
