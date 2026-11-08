"""Demo sin clave de modelo: un cliente MCP llama de verdad al servidor y se dibuja el resultado en un GIF.

Quien hace de agente es Claude Code (cliente MCP): las llamadas y las respuestas del servidor son reales; el texto final
lo redacta el propio script a partir de esas respuestas. No es una captura de pantalla: la animación es sintética.
Uso: python scripts/demo/record_mcp_demo.py [--output docs/media/demo-minima.gif]
Requiere Pillow, el stack levantado (./mk up) y MCP_ANALYST_KEY en .env. No carga datos (no ejecuta seed).
"""
import argparse, http.client, json, re, sys, textwrap, urllib.request
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
URL = "http://127.0.0.1:8090/mcp"
COLS, ROWS, FS = 100, 32, 15
BG, FG, PROMPT, DIM, OK, BAD, BLUE = (24, 26, 31), (220, 223, 228), (120, 200, 140), (130, 136, 148), (158, 206, 106), (247, 118, 142), (122, 162, 247)
BANNER = "Cliente MCP: Claude Code (sin clave de modelo) · salida real del servidor · datos sintéticos"


class Mcp:
    def __init__(self):
        env = (ROOT / ".env").read_text(encoding="utf-8").splitlines()
        self.key = next(l.split("=", 1)[1].strip() for l in env if l.startswith("MCP_ANALYST_KEY="))
        self.sid = None
        self.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "claude-code", "version": "1"}}, 1)
        self.rpc("notifications/initialized")

    def rpc(self, method, params=None, id_=None):
        body = {"jsonrpc": "2.0", "method": method}
        if id_:
            body["id"] = id_
        if params is not None:
            body["params"] = params
        headers = {"Authorization": "Bearer " + self.key, "Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        if self.sid:
            headers["Mcp-Session-Id"] = self.sid
        resp = urllib.request.urlopen(urllib.request.Request(URL, json.dumps(body).encode(), headers), timeout=90)
        self.sid = resp.headers.get("Mcp-Session-Id") or self.sid
        try:
            text = resp.read().decode()
        except http.client.IncompleteRead as e:  # el servidor cierra el flujo troceado
            text = e.partial.decode()
        m = re.search(r"data:\s*(\{.*\})", text)
        return json.loads(m.group(1)) if m else (json.loads(text) if text.strip() else None)

    def call(self, name, args):
        r = self.rpc("tools/call", {"name": name, "arguments": args}, 2)
        if "result" not in r:
            return False, json.dumps(r)
        text = r["result"]["content"][0]["text"]
        return not r["result"].get("isError"), text


def battery_sql(qid):
    """La consulta completa sale de la batería de evals (evals/battery/run.py), ya verificada contra su referencia."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("battery", ROOT / "evals/battery/run.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return next(q[3] for q in mod.QUESTIONS if q[0] == qid)


def short(sql, n=74):
    one = " ".join(sql.split())
    return one if len(one) <= n else one[:n] + " …"


def steps(mcp):
    """Devuelve [(llamada, [(línea, color)])] con las respuestas reales del servidor, y la respuesta final."""
    out = []
    ok, t = mcp.call("list_tables", {})
    tables = [f'{x["namespace"]}.{x["table"]}' for x in json.loads(t)["tables"]]
    out.append(("list_tables()", [(", ".join(tables), FG)]))

    sql = battery_sql("Q11")
    ok, t = mcp.call("run_query", {"sql": sql})
    if not ok:
        sys.exit("El cuadro de mando falló: no se graba. " + t[:200])
    rows = json.loads(t)["rows"]  # brand, views, carts, purchases, conv, revenue, lost, share, cum
    head = f'{"marca":<15}{"conv":>6}{"ingresos":>11}{"perdido carritos":>18}{"Pareto":>8}'
    lines = [(head, DIM)]
    for r in rows:
        lines.append((f"{r[0]:<15}{r[4] * 100:>5.1f}%{r[5]:>11,.0f}{r[6]:>18,.0f}{r[8] * 100:>7.1f}%".replace(",", " "), OK))
    out.append((f'run_query("{short(sql)}")', [("(5 CTE, LEFT JOIN y funciones de ventana en una sola consulta)", DIM)] + lines))

    sql2 = battery_sql("Q15")
    ok, t = mcp.call("run_query", {"sql": sql2})
    if not ok:
        sys.exit("La detección de anomalías falló: no se graba. " + t[:200])
    day = json.loads(t)["rows"][0]  # d, rev, media7, desv7, z
    out.append((f'run_query("{short(sql2)}")', [("(media y desviación de los 7 días anteriores, z-score por día)", DIM),
                                                  (f"{day[0]}  ingresos {day[1]:,.0f}  media7 {day[2]:,.0f}  z = {day[4]}".replace(",", " "), OK)]))

    bad = "SELECT * FROM read_csv('/etc/passwd')"
    ok, t = mcp.call("run_query", {"sql": bad})
    if ok:
        sys.exit("La consulta prohibida no fue rechazada: no se graba.")
    out.append((f'run_query("{bad}")', [("Rechazado por el validador: " + t[:70], BAD)]))

    top = rows[0]
    fmt = lambda n: f"{n:,.0f}".replace(",", " ")
    pct = f"{rows[-1][8] * 100:.0f}"
    cmp_ = "más de lo que ingresa" if top[6] > top[5] else "frente a lo que ingresa"
    final = [f"Cinco marcas concentran el {pct} % de los ingresos.",
             f"{top[0]} deja {fmt(top[6])} en carritos abandonados, {cmp_} ({fmt(top[5])}).",
             f"El {day[0]} los ingresos cayeron a {fmt(day[1])}, a {str(abs(day[4])).replace('.', ',')} desviaciones de lo normal: conviene investigarlo."]
    return out, final


def wrap(text):
    return textwrap.wrap(text, COLS - 2, replace_whitespace=False) or [""]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", type=Path, default=ROOT / "docs/media/demo-minima.gif")
    ap.add_argument("--capture", type=Path, default=ROOT / "docs/media/_capture_mcp.json")
    args = ap.parse_args()
    calls, final = steps(Mcp())
    args.capture.write_text(json.dumps({"calls": calls, "final": final}, ensure_ascii=False), encoding="utf-8")

    font = next((ImageFont.truetype(f, FS) for f in ("C:/Windows/Fonts/consola.ttf", "DejaVuSansMono.ttf") if Path(f).exists() or "/" not in f), ImageFont.load_default())
    cw, ch = int(font.getlength("M")), FS + 6
    W, H = COLS * cw + 24, ROWS * ch + 24
    frames, durs, screen = [], [], []

    def snap(ms):
        im = Image.new("RGB", (W, H), BG)
        d = ImageDraw.Draw(im)
        d.rectangle((0, 0, W, ch + 8), fill=(36, 40, 59))
        d.text((12, 6), BANNER, font=font, fill=BLUE)
        for i, (txt, col) in enumerate(screen[-(ROWS - 2):]):
            d.text((12, 12 + ch + 6 + i * ch), txt, font=font, fill=col)
        frames.append(im.quantize(colors=32))
        durs.append(ms)

    snap(700)
    question = "Tú: ¿Dónde estoy perdiendo dinero? Qué marcas concentran mis ingresos, cuánto dejan en carritos abandonados y si algún día se salió de lo normal."
    for n in range(8, len(question) + 1, 3):
        screen.append((question[:n], FG)); snap(45); screen.pop()
    for s in wrap(question):
        screen.append((s, FG))
    snap(900)
    screen.append(("Claude: lo analizo en el lakehouse con SQL de verdad, vía MCP (SQL abreviado en pantalla).", BLUE)); snap(1200)
    screen.append(("", FG))
    for call, lines in calls:
        text = "> " + call
        shown = wrap(text)
        for n in range(4, len(shown[0]) + 1, 4):
            screen.append((shown[0][:n], PROMPT)); snap(35); screen.pop()
        for s in shown:
            screen.append((s, PROMPT))
        snap(500)
        for line, col in lines:
            for s in wrap(line):
                screen.append(("  " + s, col)); snap(170)
        snap(1500)
    screen.append(("", FG)); screen.append(("Respuesta (redactada a partir de lo anterior):", BLUE)); snap(600)
    for line in final:
        for s in wrap(line):
            screen.append((s, FG)); snap(450)
    snap(3500)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    frames[0].save(args.output, save_all=True, append_images=frames[1:], duration=durs, loop=0, optimize=True)
    print(f"{args.output} · {len(frames)} fotogramas · {args.output.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
