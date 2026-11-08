"""infra/smoke.sh with stubs of curl and duckdb and the real jq: a FAILED connector task must fail the check (T-141)."""
import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent.parent / "infra" / "smoke.sh"
PLUGINS = [{"class": "org.apache.iceberg.connect.IcebergSinkConnector"}]

CURL = """#!/bin/sh
for a; do url=$a; done
case "$url" in
  *connector-plugins*) cat "$STATE/plugins.json" ;;
  *expand=status*) cat "$STATE/status.json" ;;
  *) echo '[]' ;;
esac
"""
DUCKDB = "#!/bin/sh\nexit 0\n"
TRACE = ("org.apache.kafka.connect.errors.ConnectException: Cannot retrieve members for consumer group: "
         "connect-iceberg-sink\n\tat org.apache.iceberg.connect.channel.CommitterImpl.hasLeaderPartition")


def status(connector="RUNNING", tasks=(("RUNNING", None),)):
    return {"iceberg-sink": {"status": {
        "name": "iceberg-sink",
        "connector": {"state": connector, "worker_id": "connect:8083"},
        "tasks": [dict({"id": i, "state": s, "worker_id": "connect:8083"}, **({"trace": t} if t else {}))
                  for i, (s, t) in enumerate(tasks)],
    }}}


def run_smoke(tmp_path, connectors):
    sh, jq = shutil.which("sh"), shutil.which("jq")
    if sh is None or jq is None:
        pytest.skip("needs a POSIX shell and jq")
    stubs = tmp_path / "stubs"
    stubs.mkdir()
    for name, body in {"curl": CURL, "duckdb": DUCKDB}.items():
        path = stubs / name
        path.write_text(body, encoding="utf-8", newline="\n")
        path.chmod(0o755)
    (tmp_path / "plugins.json").write_text(json.dumps(PLUGINS), encoding="utf-8")
    (tmp_path / "status.json").write_text(json.dumps(connectors), encoding="utf-8")
    env = dict(os.environ, STATE=tmp_path.as_posix(), PATH=f"{stubs}{os.pathsep}{os.environ['PATH']}")
    for var in ("S3_ACCESS_KEY", "S3_SECRET_KEY", "S3_REGION", "POLARIS_CLIENT_ID", "POLARIS_CLIENT_SECRET"):
        env[var] = "x"
    return subprocess.run([sh, str(SCRIPT)], env=env, capture_output=True, text=True, timeout=60)


def test_running_connector_passes(tmp_path):
    result = run_smoke(tmp_path, status())

    assert result.returncode == 0, result.stdout + result.stderr
    assert "connectors running: iceberg-sink" in result.stdout
    assert "duckdb -> polaris -> seaweedfs ok" in result.stdout


def test_failed_task_with_running_connector_fails_and_says_why(tmp_path):
    """The case seen in T-141: connector RUNNING, task 0 FAILED, container healthy."""
    result = run_smoke(tmp_path, status(tasks=[("FAILED", TRACE)]))

    assert result.returncode != 0
    out = result.stdout + result.stderr
    assert "iceberg-sink 0: FAILED" in out
    assert "Cannot retrieve members for consumer group" in out
    assert "CommitterImpl" not in out  # only the first line of the trace
    assert "./mk up" in out
    assert "duckdb -> polaris" not in result.stdout


def test_failed_connector_fails(tmp_path):
    result = run_smoke(tmp_path, status(connector="FAILED"))

    assert result.returncode != 0
    assert "iceberg-sink connector: FAILED" in result.stdout + result.stderr


def test_connector_without_tasks_fails(tmp_path):
    result = run_smoke(tmp_path, status(tasks=[]))

    assert result.returncode != 0
    assert "NO TASKS" in result.stdout + result.stderr


def test_smoke_script_drops_what_it_creates():
    """T-078: the check must leave no table nor namespace behind (needs neither sh nor jq)."""
    sql = SCRIPT.read_text(encoding="utf-8")
    for create, drop in (("CREATE SCHEMA IF NOT EXISTS lh.smoke", "DROP SCHEMA lh.smoke"),
                         ("CREATE TABLE lh.smoke.t", "DROP TABLE lh.smoke.t")):
        assert create in sql and drop in sql
        assert sql.index(drop) > sql.index(create)
    assert sql.index("DROP TABLE lh.smoke.t") < sql.index("DROP SCHEMA lh.smoke")


def test_no_connector_registered_fails(tmp_path):
    result = run_smoke(tmp_path, {})

    assert result.returncode != 0
    assert "no connectors registered" in result.stdout + result.stderr
