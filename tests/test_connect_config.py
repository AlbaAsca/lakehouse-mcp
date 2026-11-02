"""IcebergSinkFlowIT runs on the testkit's own worker: these pins keep what it proves true of the real stack (T-072)."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TESTKIT = ROOT / "testkit/src"
FLOW_IT = TESTKIT / "test/java/io/github/albaasca/lakehouse/testkit/IcebergSinkFlowIT.java"
WORKER = TESTKIT / "main/java/io/github/albaasca/lakehouse/testkit/ConnectWorker.java"


def compose_value(name):
    match = re.search(rf"^\s*{name}:\s*(\S+)\s*$", (ROOT / "compose.yaml").read_text(encoding="utf-8"), re.MULTILINE)
    assert match, f"{name} is not in compose.yaml"
    return match.group(1).strip("\"'")


def test_the_stack_flushes_as_often_as_the_flow_test():
    """At the default 60 s an idle sink misses every 30 s commit window and writes nothing."""
    tested = re.search(r'"CONNECT_OFFSET_FLUSH_INTERVAL_MS", "(\d+)"', WORKER.read_text(encoding="utf-8"))

    assert tested, "ConnectWorker no longer sets the flush interval"
    assert "ConnectWorker.start(" in FLOW_IT.read_text(encoding="utf-8"), "IcebergSinkFlowIT no longer uses ConnectWorker"
    assert compose_value("CONNECT_OFFSET_FLUSH_INTERVAL_MS") == tested.group(1) == "1000"


def test_the_worker_stays_on_a_version_where_the_sink_starts():
    """cp-kafka-connect 8.4.0 kills the task with NoClassDefFoundError: raising it needs IcebergSinkFlowIT first."""
    dockerfile = (ROOT / "infra/connect/Dockerfile").read_text(encoding="utf-8")

    assert re.findall(r"^FROM confluentinc/cp-kafka-connect:(\S+)", dockerfile, re.MULTILINE) == ["8.1.0"]


def test_the_plugin_is_built_with_the_version_it_announces():
    """Without version.txt the 1.12.0 tag announces itself as 1.13.0-SNAPSHOT."""
    dockerfile = (ROOT / "infra/connect/Dockerfile").read_text(encoding="utf-8")

    assert "ARG ICEBERG_VERSION=1.12.0" in dockerfile
    assert 'echo "${ICEBERG_VERSION}" > version.txt' in dockerfile
