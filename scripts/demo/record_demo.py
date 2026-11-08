"""Graba la demo sin intervención: ejecuta las preguntas de verdad con ./mk ask y dibuja la terminal en un GIF.

No es una captura de pantalla: la salida es la real (filtrada de ruido de compilación), la animación es sintética.
Uso: python scripts/demo/record_demo.py [--output docs/media/demo-minima.gif]
Requiere Pillow, el stack levantado (./mk up) y la tabla ya cargada (NO ejecuta seed).
"""
import argparse, re, subprocess, sys, textwrap
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


def run(question):
    p = subprocess.run([str(ROOT / ("mk.cmd" if sys.platform == "win32" else "mk")), "ask", f"Q={question}"], cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if p.returncode:
        sys.exit(f"mk ask falló ({p.returncode}) con «{question}»; no se graba.\n{p.stderr[-800:]}")
    lines = [l.rstrip() for l in (p.stdout + p.stderr).splitlines() if l.strip() and not NOISE.match(l)]
    for l in lines:
        if SECRET.search(l):
            sys.exit("Posible secreto en la salida; no se graba.")
    return lines


def wrap(lines):
    out = []
    for l in lines:
        out += textwrap.wrap(l, COLS, replace_whitespace=False, drop_whitespace=False) or [""]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", type=Path, default=ROOT / "docs/media/demo-minima.gif")
    out = ap.parse_args().output
    font = next((ImageFont.truetype(f, FS) for f in ("C:/Windows/Fonts/consola.ttf", "DejaVuSansMono.ttf") if Path(f).exists() or "/" not in f), ImageFont.load_default())
    cw, ch = int(font.getlength("M")), FS + 6
    W, H = COLS * cw + 24, ROWS * ch + 24
    frames, durs, screen = [], [], []

    def snap(ms):
        im = Image.new("RGB", (W, H), BG)
        d = ImageDraw.Draw(im)
        for i, (txt, col) in enumerate(screen[-ROWS:]):
            d.text((12, 12 + i * ch), txt, font=font, fill=col)
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
        for l in wrap(run(q)):
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
