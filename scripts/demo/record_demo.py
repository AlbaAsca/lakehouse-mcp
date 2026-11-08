"""Graba la demo sin intervención: ejecuta las preguntas de verdad con ./mk ask y dibuja la terminal en un GIF.

No es una captura de pantalla: la salida es la real (filtrada de ruido de compilación), la animación es sintética.
Uso: python scripts/demo/record_demo.py [--output docs/media/demo-minima.gif]
Requiere Pillow, el stack levantado (./mk up) y la tabla ya cargada (NO ejecuta seed).
"""
import argparse, json, re, subprocess, sys, textwrap
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
QUESTIONS = [
    "How many rows does the events table have?",
    "Which five brands have the highest purchase revenue? Show the amounts.",
]
COLS, ROWS, FS = 100, 26, 15
BG, FG, PROMPT, DIM = (24, 26, 31), (220, 223, 228), (120, 200, 140), (130, 136, 148)
NOISE = re.compile(r"^(\s*(Container|Network|Volume|Image)\s|\[usage\] model call|#\d+|time=|\s+at |\s*\.\.\.|make:|WARN|\[agent\] resolving)")
SECRET = re.compile(r"(?i)(key|token|secret)\s*[=:]\s*\S{8,}")


CACHE = ROOT / "docs/media/_capture.json"  # salida cruda de la última ejecución real; no se sube


MOCK = {
    QUESTIONS[0]: ["There are 50,000 rows in the events table.", "Tools used: `run_query`"],
    QUESTIONS[1]: [
        "The five brands with the highest purchase revenue are:",
        "1. **synth-nimbora**: 702,942.00",
        "2. **synth-velkaro**: 371,961.23",
        "3. **synth-ostrina**: 285,923.12",
        "4. **synth-quorvo**: 280,932.97",
        "5. **synth-lumbrel**: 212,114.06",
        "Tools used: `list_metrics`, `get_metric` (metric: `top_brands`).",
    ],
}
BANNER = "MAQUETA SIMULADA - NO ES SALIDA REAL (se sustituira por la grabacion real)"


def run(question, replay, mock=False):
    if mock:
        return MOCK[question]
    cache = json.loads(CACHE.read_text(encoding="utf-8")) if CACHE.exists() else {}
    if replay and question in cache:
        raw = cache[question]
    else:
        raw = capture(question)
        cache[question] = raw
        CACHE.write_text(json.dumps(cache, ensure_ascii=False), encoding="utf-8")
    lines = [l.rstrip() for l in raw.splitlines() if l.strip() and not NOISE.match(l)]
    for l in lines:
        if SECRET.search(l):
            sys.exit("Posible secreto en la salida; no se graba.")
    return lines


def capture(question):
    p = subprocess.run([str(ROOT / ("mk.cmd" if sys.platform == "win32" else "mk")), "ask", f"Q={question}"], cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if p.returncode:
        sys.exit(f"mk ask falló ({p.returncode}) con «{question}»; no se graba.\n{p.stderr[-800:]}")
    return p.stdout + p.stderr


def wrap(lines):
    out = []
    for l in lines:
        out += textwrap.wrap(l, COLS, replace_whitespace=False, drop_whitespace=False) or [""]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mock", action="store_true", help="maqueta con texto fijo y aviso visible; no llama al modelo")
    ap.add_argument("--replay", action="store_true", help="reusa la última salida real guardada (no gasta cupo del modelo)")
    ap.add_argument("--output", type=Path, default=ROOT / "docs/media/demo-minima.gif")
    args = ap.parse_args()
    out = args.output
    if args.mock and out.name == "demo-minima.gif":
        out = out.with_name("demo-minima-maqueta.gif")
    font = next((ImageFont.truetype(f, FS) for f in ("C:/Windows/Fonts/consola.ttf", "DejaVuSansMono.ttf") if Path(f).exists() or "/" not in f), ImageFont.load_default())
    cw, ch = int(font.getlength("M")), FS + 6
    W, H = COLS * cw + 24, ROWS * ch + 24
    frames, durs, screen = [], [], []

    def snap(ms):
        im = Image.new("RGB", (W, H), BG)
        d = ImageDraw.Draw(im)
        if args.mock:
            d.rectangle((0, 0, W, ch + 8), fill=(150, 40, 40))
            d.text((12, 6), BANNER, font=font, fill=(255, 255, 255))
        for i, (txt, col) in enumerate(screen[-(ROWS - 2):]):
            d.text((12, 12 + ch + 6 + i * ch), txt, font=font, fill=col)
        frames.append(im.quantize(colors=32))
        durs.append(ms)

    snap(600)
    for q in QUESTIONS:
        cmd = f'$ ./mk ask Q="{q}"'
        for n in range(2, len(cmd) + 1, 3):
            screen[:] = [s for s in screen if s is not None]
            base = screen[:]
            screen.append((cmd[:n], PROMPT)); snap(40); screen[:] = base
        screen.append((cmd, PROMPT)); snap(500)
        screen.append(("  ...(el agente piensa y consulta; espera recortada)", DIM)); snap(900); screen.pop()
        for l in wrap(run(q, args.replay, args.mock)):
            col = DIM if l.startswith("[usage]") else FG
            screen.append((l, col)); snap(160)
        snap(2200)
        screen.append(("", FG))
    snap(2500)
    out.parent.mkdir(parents=True, exist_ok=True)
    frames[0].save(out, save_all=True, append_images=frames[1:], duration=durs, loop=0, optimize=True)
    print(f"{out} · {len(frames)} fotogramas · {out.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
