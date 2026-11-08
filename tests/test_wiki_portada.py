"""T-144: la portada de la wiki nombra la lectura del catálogo y su ancla existe."""
import re
from pathlib import Path

WIKI = Path(__file__).resolve().parent.parent / "docs" / "wiki"


def _slug(h):
    s = re.sub(r"[^\w\s-]", "", h.strip().lower())
    return re.sub(r"\s", "-", s)


def test_portada_menciona_lectura_del_catalogo_con_enlace_valido():
    lineas = (WIKI / "README.md").read_text(encoding="utf-8").splitlines()
    fila = next(l for l in lineas if "Servidor MCP" in l and l.startswith("|"))
    assert "catálogo" in fila.lower()
    m = re.search(r"\(componentes/duckdb\.md#([^)]+)\)", fila)
    assert m, fila
    heads = [_slug(l.lstrip("#")) for l in (WIKI / "componentes" / "duckdb.md").read_text(encoding="utf-8").splitlines() if l.startswith("#")]
    assert m.group(1) in heads
