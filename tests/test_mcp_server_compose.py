"""T-093: McpServerImageIT proves the image "as compose.yaml runs it", but it never reads compose.yaml.

Its variables and its build context are written by hand in the test, so it stays green if the service
or the root .dockerignore change. These checks tie the four files together, element by element.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IT = (ROOT / "mcp-server/src/test/java/io/github/albaasca/lakehouse/mcp/McpServerImageIT.java").read_text(encoding="utf-8")
APPLICATION = (ROOT / "mcp-server/src/main/resources/application.yaml").read_text(encoding="utf-8")
DOCKERFILE = (ROOT / "infra/mcp-server/Dockerfile").read_text(encoding="utf-8")
ENV_EXAMPLE = (ROOT / ".env.example").read_text(encoding="utf-8")
COMPOSE = (ROOT / "compose.yaml").read_text(encoding="utf-8")
DOCKERIGNORE = (ROOT / ".dockerignore").read_text(encoding="utf-8")
ARCHITECTURE = (ROOT / "docs/architecture.md").read_text(encoding="utf-8")


def service(text=None):
    match = re.search(r"^  mcp-server:\n((?:(?:    .*)?\n)+?)(?=^  \S|^\S)", text or COMPOSE, re.MULTILINE)
    assert match, "mcp-server is not a service of compose.yaml"
    return match.group(1)


def compose_environment():
    block = re.search(r"^    environment:\n((?:      .*\n)+)", service(), re.MULTILINE)
    assert block, "mcp-server has no environment block"
    return dict(re.findall(r"^      (\w+): (.+)$", block.group(1), re.MULTILINE))


def it_environment():
    block = re.search(r"\.withEnv\(Map\.of\((.*?)\)\)", IT, re.DOTALL)
    assert block, "McpServerImageIT no longer sets its variables with withEnv(Map.of(...))"
    return re.findall(r'"(\w+)",\s*', block.group(1))


def dockerignore_paths():
    return [line[1:] for line in DOCKERIGNORE.splitlines() if line.startswith("!")]


def it_context():
    block = re.search(r"CONTEXT = List\.of\((.*?)\);", IT, re.DOTALL)
    assert block, "McpServerImageIT no longer lists its build context"
    return re.findall(r'"([^"]+)"', block.group(1))


def dockerfile_sources():
    """What the build stage copies from the context, leaving out COPY --from=<stage>."""
    sources = []
    for line in DOCKERFILE.splitlines():
        if line.startswith("COPY ") and "--from=" not in line:
            sources.extend(line.split()[1:-1])
    return sources


def test_the_service_passes_every_variable_the_server_requires():
    required = set(re.findall(r"\$\{(\w+)\}", APPLICATION))

    assert required == {"POLARIS_CLIENT_ID", "POLARIS_CLIENT_SECRET", "S3_ACCESS_KEY", "S3_SECRET_KEY", "S3_REGION"}
    assert required <= set(compose_environment())


def test_the_required_variables_come_from_the_env_example():
    for name, value in compose_environment().items():
        if name != "MCP_SERVER_ADDRESS":
            assert value == f"${{{name}}}"
            assert re.search(rf"^{name}=\S", ENV_EXAMPLE, re.MULTILINE), f"{name} is not in .env.example"


def test_the_image_test_runs_the_same_variables_as_the_service():
    assert sorted(it_environment()) == sorted(compose_environment())
    assert compose_environment()["MCP_SERVER_ADDRESS"] == "0.0.0.0"
    assert '"MCP_SERVER_ADDRESS", "0.0.0.0"' in IT


def test_the_port_is_published_only_on_the_loopback_of_the_host():
    assert re.findall(r"^    ports: (.+)$", service(), re.MULTILINE) == ['["127.0.0.1:8090:8090"]']


def test_the_service_waits_for_the_catalog_and_has_no_bind_mounts():
    assert "polaris: {condition: service_healthy}" in service()
    assert "lakehouse-init: {condition: service_completed_successfully}" in service()
    assert "volumes:" not in service()


def test_the_service_builds_the_dockerfile_from_the_root():
    assert "      context: .\n      dockerfile: infra/mcp-server/Dockerfile\n" in service()


def test_the_image_test_sends_the_same_context_as_the_dockerignore():
    expected = set(dockerignore_paths()) - {"*/pom.xml"}
    context = set(it_context())

    assert {path for path in context if not path.endswith("/pom.xml")} == expected
    assert {path for path in context if path.endswith("/pom.xml")} == {
        source for source in dockerfile_sources() if source.endswith("/pom.xml")
    }


def test_the_dockerignore_lets_through_everything_the_dockerfile_copies_and_nothing_secret():
    allowed = dockerignore_paths()

    assert "*" in DOCKERIGNORE.splitlines(), "without the leading *, .env and .git enter the build context"
    assert not {".env", ".git"} & set(allowed)
    for source in dockerfile_sources():
        assert any(
            source == path or source.startswith(path + "/") or (path == "*/pom.xml" and re.fullmatch(r"[^/]+/pom\.xml", source))
            for path in allowed
        ), f"{source} is copied by the Dockerfile but excluded by .dockerignore"


def test_the_heap_leaves_room_for_duckdb_inside_the_budget():
    heap = re.search(r'"-Xmx(\d+)m"', DOCKERFILE)

    assert heap and heap.group(1) == "256"
    assert "| mcp-server | 1 GB |" in ARCHITECTURE
    assert "JVM 256 MB" in ARCHITECTURE
    assert '"-Xmx256m"' in IT


def test_the_helper_finds_only_the_block_of_the_service():
    compose = "services:\n  a:\n    x: 1\n\n  mcp-server:\n    y: 2\n\n    z: 3\n\n  b:\n    w: 4\n"

    assert service(compose) == "    y: 2\n\n    z: 3\n\n"
