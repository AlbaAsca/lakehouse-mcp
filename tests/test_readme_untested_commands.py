"""T-075: las órdenes `./mk seed` (CSV real) del README deben marcarse «sin probar»."""
import re
from pathlib import Path

README = Path(__file__).resolve().parent.parent / "README.md"


def test_mk_seed_rows_marked_untested():
    rows = [l for l in README.read_text(encoding="utf-8").splitlines()
            if re.search(r"`\./mk seed( |`)", l)]
    assert len(rows) >= 2
    for row in rows:
        assert "sin probar" in row.lower(), row
