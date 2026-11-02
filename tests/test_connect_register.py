"""connect/register.sh against stubs of curl, jq and sleep: which answers of Connect it waits for."""
import os
import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent.parent / "connect" / "register.sh"
ATTEMPTS = 60

CURL = """#!/bin/sh
# Answers the next code of the list; the last one repeats.
n=$(cat "$STATE/calls" 2>/dev/null || echo 0)
n=$((n + 1))
echo "$n" > "$STATE/calls"
code=$(sed -n "${n}p" "$STATE/codes")
[ -n "$code" ] || code=$(tail -n 1 "$STATE/codes")
: > /tmp/out
printf %s "$code"
"""
JQ = '#!/bin/sh\necho iceberg-sink\n'
SLEEP = '#!/bin/sh\nexit 0\n'


def run_register(tmp_path, codes):
    """Runs the script with Connect answering `codes`; returns (result, number of PUTs)."""
    sh = shutil.which("sh")
    if sh is None:
        pytest.skip("needs a POSIX shell")
    stubs = tmp_path / "stubs"
    stubs.mkdir()
    for name, body in {"curl": CURL, "jq": JQ, "sleep": SLEEP}.items():
        path = stubs / name
        path.write_text(body, encoding="utf-8", newline="\n")
        path.chmod(0o755)
    (tmp_path / "codes").write_text("\n".join(codes) + "\n", encoding="utf-8", newline="\n")
    env = dict(os.environ, STATE=tmp_path.as_posix(), PATH=f"{stubs}{os.pathsep}{os.environ['PATH']}")
    result = subprocess.run([sh, str(SCRIPT)], env=env, capture_output=True, text=True, timeout=60)
    calls = int((tmp_path / "calls").read_text(encoding="utf-8"))
    return result, calls


@pytest.mark.parametrize("not_ready", ["000", "404", "409", "503"])
def test_waits_while_connect_is_starting(tmp_path, not_ready):
    result, calls = run_register(tmp_path, [not_ready, not_ready, "201"])

    assert result.returncode == 0, result.stdout + result.stderr
    assert "iceberg-sink: 201" in result.stdout
    assert calls == 3


def test_a_rejected_config_stops_at_once(tmp_path):
    result, calls = run_register(tmp_path, ["400"])

    assert result.returncode == 1
    assert "iceberg-sink: 400" in result.stdout
    assert calls == 1


def test_gives_up_if_connect_never_gets_ready(tmp_path):
    result, calls = run_register(tmp_path, ["404"])

    assert result.returncode == 1
    assert "last answer 404" in result.stdout
    assert calls == ATTEMPTS
