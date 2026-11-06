"""Protocolo de permisos entre agentes: cuando al runner le deniegan un comando a un agente, se clasifica y se actúa.

  · encadenado  → nunca se concede tal cual; se le explica al agente cómo hacerlo (un comando por llamada, --env, Write).
  · prohibido   → nunca (publicar, borrar, instalar, red, commits): se anota el motivo para que no lo reintente.
  · seguro      → solo lectura dentro del proyecto: se concede automáticamente y queda registrado.
  · a revisar   → el resto: se abre una tarjeta «Solicitud de permiso» con botones Conceder / Denegar en el panel.

Lo concedido y lo denegado vive en board/permissions.json (con quién, cuándo y por qué) y el runner lo suma a sus listas.
Uso: python board/permissions.py list | grant "<patrón>" --why "..." | deny "<patrón>" --why "..."
"""
import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path

F = Path(__file__).with_name("permissions.json")

CHAIN = re.compile(r"(&&|\|\||;|\||\n|<<|>>?|`|\$\()")
ENV_PREFIX = re.compile(r"^\s*[A-Za-z_][A-Za-z0-9_]*=\S*\s")
NEVER = [
    (re.compile(r"\bgit\s+(push|commit|add|reset|checkout|clean|rebase|merge|tag\s+(-a|-d|-f)|branch\s+-[dD])\b"),
     "git que escribe: los commits y tags los hace el runner"),
    (re.compile(r"\b(competitions\s+submit|kernels\s+push|datasets\s+(create|version))\b|mcp__kaggle"),
     "publicar fuera del proyecto: solo el responsable"),
    (re.compile(r"(^|\s)(rm|del|rmdir|Remove-Item|mv|Move-Item|cp|Copy-Item)\b"), "borrar o mover ficheros: usa Write/Edit"),
    (re.compile(r"\b(pip|npm|npx|winget|choco|conda)\b|-m\s+pip\b"), "instalar software: lo decide el responsable"),
    (re.compile(r"\b(curl|wget|Invoke-WebRequest|iwr|ssh|scp)\b"), "red: usa WebFetch o pide los datos al responsable"),
    # Anclados al inicio para no confundir un comando con un texto que lo menciona (p. ej. la descripción de una tarjeta).
    (re.compile(r"^(\S*[\\/])?docker(\.exe)?\s(.*\s)?(down|prune|rm|rmi)(\s|$)"), "borra contenedores o datos: solo el responsable"),
    (re.compile(r"^(\S*[\\/])?(mk(\.cmd)?|make)\s(.*\s)?down(\s|$)"), "apaga el stack: solo el responsable"),
    (re.compile(r"^(\S*[\\/])?gh(\.exe)?\s(.*\s)?(delete|DELETE)(\s|$)"), "borra en GitHub: solo el responsable"),
]
# Lo mismo que los tres últimos de NEVER, en el formato de la CLI: las reglas de denegar prevalecen sobre cualquier concesión.
# Palabra exacta, no «down*», que también cazaría ./mk download-data.
NEVER_WORDS = {
    "docker": ("down", "prune", "rm", "rmi"),
    "./mk": ("down",), "mk": ("down",), "./mk.cmd": ("down",), "mk.cmd": ("down",), ".\\mk.cmd": ("down",), "make": ("down",),
    "gh": ("delete", "DELETE"),
}
SAFE = [  # solo lectura, o build y tests dentro de los contenedores del proyecto
    re.compile(r"^(\.venv[\\/]Scripts[\\/])?python(\.exe)?\s"),
    re.compile(r"^git\s+(status|diff|log|show|blame|ls-files|rev-parse|tag\s+(--list|-l)|branch\s+(--list|-a|-v)|describe)\b"),
    re.compile(r"^(ls|dir|tree|wc|head|tail|cat|grep|rg|sort|uniq|du|file|stat|diff)\b"),
    re.compile(r"^(\./|\.\\)?mvnw(\.cmd)?\s"),
    re.compile(r"^(\./|\.\\)?mk(\.cmd)?\s+(test(\s+MODULE=[a-z][a-z0-9-]*)?|test-board|evals)\s*$"),
    re.compile(r"^make\s+(test(\s+MODULE=[a-z][a-z0-9-]*)?|test-board|evals)\s*$"),
    re.compile(r"^docker\s+compose\s+(ps|logs)\b"),
    re.compile(r"^find\s(?!.*(-delete|-exec|-ok))"),
    re.compile(r"^(Get-ChildItem|Get-Content|Select-String|Measure-Object|Get-Item)\b"),
]


def load():
    if not F.exists():
        return {"allowed": [], "denied": []}
    return json.loads(F.read_text(encoding="utf-8"))


def save(d):
    F.write_text(json.dumps(d, ensure_ascii=False, indent=2), encoding="utf-8")


def pattern(cmd):
    """Patrón de permiso lo más estrecho que siga siendo útil: git show 98cb → «git show*»; git tag --list x → «git tag --list*»;
    kaggle.exe kernels list … → «…kaggle.exe kernels list*»; Rscript foo.R → «Rscript *»."""
    toks = cmd.strip().split()
    if not toks:
        return None
    if re.search(r"(^|[\\/])kaggle(\.exe)?$", toks[0]):
        return " ".join(toks[:3]) + "*"
    if re.search(r"(^|[\\/])git$", toks[0]) and len(toks) > 1:
        n = 3 if len(toks) > 2 and toks[2].startswith("-") else 2
        return " ".join(toks[:n]) + "*"
    if len(toks) > 2 and toks[1] == "-m":
        return " ".join(toks[:3]) + "*"
    return f"{toks[0]} *"


QUOTED = re.compile(r'"[^"]*"|\'[^\']*\'')
# Intérpretes de órdenes: lo que va entre comillas es código que se ejecuta, no texto, y se revisa entero.
SHELL = re.compile(r"^(\S*[\\/])?(bash|sh|zsh|cmd|powershell|pwsh)(\.exe)?(\s|$)", re.I)


def unquoted(c):
    """El comando sin el contenido entre comillas: «del», «rm» o «cp» dentro de un --desc es texto, no un comando."""
    return re.sub(r"[\"']", " ", c) if SHELL.match(c) else QUOTED.sub('""', c)


def classify(cmd):
    """(categoría, patrón, motivo) con categoría en encadenado | prohibido | seguro | revisar."""
    c = cmd.strip()
    bare = unquoted(c)
    for rx, why in NEVER:
        if rx.search(bare):
            return "prohibido", pattern(c), why
    if ENV_PREFIX.match(c):
        return "encadenado", None, "variables delante del comando: pásalas como argumento del script"
    if CHAIN.search(c):
        return "encadenado", None, ("varios comandos, tuberías, redirecciones o heredoc: lanza cada comando por separado y "
                                    "crea ficheros con Write/Edit")
    if any(rx.search(c) for rx in SAFE):
        return "seguro", pattern(c), "solo lectura dentro del proyecto"
    return "revisar", pattern(c), "no está en ninguna lista: lo valida el consultor o el responsable"


def rules(d=None):
    """Patrones concedidos y denegados en el formato de la CLI (Bash y PowerShell)."""
    d = d or load()
    wrap = lambda ps: [f"{tool}({p})" for p in ps for tool in ("Bash", "PowerShell")]
    return wrap([x["pattern"] for x in d["allowed"]]), wrap([x["pattern"] for x in d["denied"]])


def never_rules():
    """Reglas de denegar de los comandos destructivos: cubren la palabra justo detrás del comando o tras otras opciones."""
    forms = ("{c} {w}", "{c} {w} *", "{c} * {w}", "{c} * {w} *")
    return [f"{tool}({f.format(c=c, w=w)})" for c, words in NEVER_WORDS.items() for w in words for f in forms
            for tool in ("Bash", "PowerShell")]


def record(kind, pat, why, by, d=None, save_it=True):
    d = d or load()
    other = "denied" if kind == "allowed" else "allowed"
    d[other] = [x for x in d[other] if x["pattern"] != pat]
    if not any(x["pattern"] == pat for x in d[kind]):
        d[kind].append({"pattern": pat, "why": why, "by": by,
                        "at": datetime.now().astimezone().isoformat(timespec="seconds")})
    if save_it:
        save(d)
    return d


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser()
    sp = ap.add_subparsers(dest="cmd", required=True)
    sp.add_parser("list")
    for k in ("grant", "deny", "check"):
        p = sp.add_parser(k)
        p.add_argument("target")
        p.add_argument("--why", default="")
        p.add_argument("--by", default="consultor")
    o = ap.parse_args(argv)
    if o.cmd == "list":
        d = load()
        for k in ("allowed", "denied"):
            for x in d[k]:
                print(f"{'✓' if k == 'allowed' else '✗'} {x['pattern']:45} {x['by']:10} {x['at'][:16]}  {x['why']}")
    elif o.cmd == "check":
        print(" · ".join(str(x) for x in classify(o.target)))
    else:
        record("allowed" if o.cmd == "grant" else "denied", o.target, o.why, o.by)
        print("ok")


if __name__ == "__main__":
    main()
