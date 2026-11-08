"""Runs tests/test_smoke.py inside the tools image (real jq, no pytest): python3 scripts/analysis/run_smoke_tests_in_tools.py"""
import sys
import tempfile
import types
from pathlib import Path

fake = types.ModuleType("pytest")


def _skip(msg):
    raise SystemExit(f"SKIP: {msg}")


fake.skip = _skip
sys.modules["pytest"] = fake
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tests"))
import test_smoke  # noqa: E402

failed = 0
for name in sorted(n for n in dir(test_smoke) if n.startswith("test_")):
    with tempfile.TemporaryDirectory() as d:
        try:
            getattr(test_smoke, name)(Path(d))
            print("PASS", name)
        except AssertionError as e:
            failed += 1
            print("FAIL", name, e)
sys.exit(1 if failed else 0)
