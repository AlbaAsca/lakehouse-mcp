"""Llama a una herramienta del servidor MCP y apunta la llamada y su respuesta en un registro (JSON por línea).

Uso: python scripts/demo/mcp_call.py <herramienta> '<argumentos en JSON>'
El registro va a la ruta de la variable LAKEHOUSE_TRANSCRIPT (si no existe, no se registra). Sirve para que un agente
cualquiera use el servidor sin tocar la clave, y para dibujar después lo que hizo (record_mcp_demo.py --transcript).
"""
import importlib.util, json, os, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("rec", ROOT / "scripts/demo/record_mcp_demo.py")
rec = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rec)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    name, args = sys.argv[1], json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
    ok, text = rec.Mcp().call(name, args)
    log = os.environ.get("LAKEHOUSE_TRANSCRIPT")
    if log:
        with open(log, "a", encoding="utf-8") as f:
            f.write(json.dumps({"t": time.time(), "tool": name, "args": args, "ok": ok, "result": text}, ensure_ascii=False) + "\n")
    print(text if ok else "ERROR: " + text)


if __name__ == "__main__":
    main()
