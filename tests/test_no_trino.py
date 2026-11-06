"""T-108: Trino and the benchmark are gone; only the explanation of the decision may mention them.

The module list of the root pom is the reference: every Dockerfile and every image test that copies
the poms by hand must copy exactly those, element by element, so a removed module cannot linger.
"""
import re
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
MODULES = re.findall(r"<module>([^<]+)</module>", (ROOT / "pom.xml").read_text(encoding="utf-8"))
DOCKERFILES = ["infra/agent/Dockerfile", "infra/maintenance/Dockerfile", "infra/mcp-server/Dockerfile"]
IMAGE_TESTS = [
    "agent/src/test/java/io/github/albaasca/lakehouse/agent/AgentIT.java",
    "agent/src/test/java/io/github/albaasca/lakehouse/agent/AgentImageIT.java",
    "maintenance/src/test/java/io/github/albaasca/lakehouse/maintenance/ExpireSnapshotsIT.java",
    "mcp-server/src/test/java/io/github/albaasca/lakehouse/mcp/McpServerImageIT.java",
]
# Where the decision is explained (or, in the spike, recorded as history). Nothing else may name Trino.
ALLOWED = {
    "docs/architecture.md",
    "docs/wiki/05-decisiones.md",
    "docs/spike-duckdb-iceberg.md",
    "docs/wiki/componentes/duckdb.md",  # only the link to «por qué no hay Trino»
    "docs/wiki/componentes/trino.md",  # pending deletion, see test_removed_files_are_gone
    "tests/test_no_trino.py",
}


def text(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


def tracked():
    out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True).stdout
    return out.split()


def test_root_pom_has_no_bench_module():
    assert "bench" not in MODULES
    assert MODULES, "no modules parsed from pom.xml"


def test_compose_has_no_trino_nor_bench_profile():
    compose = text("compose.yaml")
    assert not re.search(r"(?i)trino", compose)
    assert not re.search(r"profiles:\s*\[[^\]]*bench", compose)


def test_makefile_has_no_bench_target():
    makefile = text("Makefile")
    assert not re.search(r"\bbench\b", makefile)


@pytest.mark.parametrize("dockerfile", DOCKERFILES)
def test_dockerfiles_copy_exactly_the_module_poms(dockerfile):
    copied = re.findall(r"^COPY (\S+)/pom\.xml ", text(dockerfile), re.MULTILINE)
    assert copied == MODULES


@pytest.mark.parametrize("it", IMAGE_TESTS)
def test_image_tests_copy_exactly_the_module_poms(it):
    block = re.search(r"CONTEXT = List\.of\((.*?)\);", text(it), re.DOTALL)
    assert block, f"{it}: no CONTEXT list"
    poms = re.findall(r'"(\w[\w-]*)/pom\.xml"', block.group(1))
    assert poms == MODULES


def test_trino_only_named_where_the_decision_is_explained():
    offenders = []
    for rel in tracked():
        if rel in ALLOWED or rel.startswith("board/") or rel.startswith("bench/"):
            continue
        path = ROOT / rel
        try:
            content = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, FileNotFoundError):
            continue
        if re.search(r"(?i)trino", content):
            offenders.append(rel)
    assert offenders == []


def test_nothing_links_to_the_trino_page():
    links = [rel for rel in tracked() if rel.endswith(".md") and rel != "docs/wiki/componentes/trino.md"
             and "trino.md" in text(rel)]
    assert links == []


@pytest.mark.xfail(strict=True, reason="T-169: bench/ and trino.md wait for someone allowed to delete files")
def test_removed_files_are_gone():
    left = [rel for rel in tracked() if rel.startswith("bench/") or rel == "docs/wiki/componentes/trino.md"]
    assert left == []
