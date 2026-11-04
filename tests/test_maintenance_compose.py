"""T-105: ExpireSnapshotsIT runs the image with its variables and context written by hand, and never reads compose.yaml.

These checks tie the service, the test, the Dockerfile and the root .dockerignore together, element by element.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IT = (ROOT / "maintenance/src/test/java/io/github/albaasca/lakehouse/maintenance/ExpireSnapshotsIT.java").read_text(encoding="utf-8")
COMMAND = (ROOT / "maintenance/src/main/java/io/github/albaasca/lakehouse/maintenance/ExpireSnapshotsCommand.java").read_text(encoding="utf-8")
DOCKERFILE = (ROOT / "infra/maintenance/Dockerfile").read_text(encoding="utf-8")
ENV_EXAMPLE = (ROOT / ".env.example").read_text(encoding="utf-8")
COMPOSE = (ROOT / "compose.yaml").read_text(encoding="utf-8")
DOCKERIGNORE = (ROOT / ".dockerignore").read_text(encoding="utf-8")
MAKEFILE = (ROOT / "Makefile").read_text(encoding="utf-8")
ROOT_POM = (ROOT / "pom.xml").read_text(encoding="utf-8")
SHARED = {"pom.xml", "*/pom.xml", "testkit/src/main"}


def service():
    match = re.search(r"^  expire-snapshots:\n((?:(?:    .*)?\n)+?)(?=^  \S|^\S)", COMPOSE, re.MULTILINE)
    assert match, "expire-snapshots is not a service of compose.yaml"
    return match.group(1)


def compose_environment():
    block = re.search(r"^    environment:\n((?:      .*\n)+)", service(), re.MULTILINE)
    assert block, "expire-snapshots has no environment block"
    return dict(re.findall(r"^      (\w+): (.+)$", block.group(1), re.MULTILINE))


def it_context():
    block = re.search(r"CONTEXT = List\.of\((.*?)\);", IT, re.DOTALL)
    assert block, "ExpireSnapshotsIT no longer lists its build context"
    return set(re.findall(r'"([^"]+)"', block.group(1)))


def dockerfile_sources():
    """What the build stage copies from the context, leaving out COPY --from=<stage>."""
    sources = set()
    for line in DOCKERFILE.splitlines():
        if line.startswith("COPY ") and "--from=" not in line:
            sources.update(line.split()[1:-1])
    return sources


def test_the_service_passes_every_variable_the_command_requires():
    required = set(re.findall(r'required\(env, "(\w+)"\)', COMMAND))

    assert required == {"POLARIS_CLIENT_ID", "POLARIS_CLIENT_SECRET", "POLARIS_REALM", "S3_ACCESS_KEY", "S3_SECRET_KEY", "S3_REGION"}
    assert set(compose_environment()) == required


def test_the_variables_come_from_the_env_example():
    for name, value in compose_environment().items():
        assert value == f"${{{name}}}"
        assert re.search(rf"^{name}=\S", ENV_EXAMPLE, re.MULTILINE), f"{name} is not in .env.example"


def test_the_image_test_runs_the_same_variables_as_the_service():
    block = re.search(r"\.withEnv\(Map\.of\((.*?)\)\)", IT, re.DOTALL)

    assert block, "ExpireSnapshotsIT no longer sets its variables with withEnv(Map.of(...))"
    assert set(re.findall(r'"(\w+)",\s*', block.group(1))) == set(compose_environment())


def test_it_only_runs_when_asked():
    assert re.findall(r"^    profiles: (.+)$", service(), re.MULTILINE) == ["[maintenance]"]
    assert 'restart: "no"' in service()
    assert "ports:" not in service()
    assert "volumes:" not in service()


def test_the_service_builds_the_dockerfile_from_the_root():
    assert "      context: .\n      dockerfile: infra/maintenance/Dockerfile\n" in service()


def test_the_make_target_runs_the_service():
    assert re.search(r"^expire:\n\tdocker compose run --rm --build expire-snapshots --table \$\(TABLE\) --retention \$\(RETENTION\)$",
                     MAKEFILE, re.MULTILINE)
    assert re.search(r"^RETENTION \?= P7D$", MAKEFILE, re.MULTILINE)


def test_the_image_test_sends_what_the_dockerfile_copies():
    context = it_context()

    assert {path for path in context if path.endswith("pom.xml")} == {
        source for source in dockerfile_sources() if source.endswith("pom.xml")
    }
    assert {"maintenance/src/main", "testkit/src/main", "infra/maintenance"} <= context


def test_the_dockerignore_lets_through_the_code_and_nothing_secret():
    allowed = {line[1:] for line in DOCKERIGNORE.splitlines() if line.startswith("!")}

    assert "*" in DOCKERIGNORE.splitlines(), "without the leading *, .env and .git enter the build context"
    assert {"maintenance/src/main", "infra/maintenance"} | SHARED <= allowed
    assert not {".env", ".git"} & allowed


def test_the_dockerfile_declares_every_module_of_the_parent_pom():
    modules = re.findall(r"<module>(\w[\w-]*)</module>", ROOT_POM)

    assert "maintenance" in modules
    assert {f"{module}/pom.xml" for module in modules} <= dockerfile_sources()


def test_the_library_is_the_same_version_as_the_sink():
    sink = (ROOT / "infra/connect/Dockerfile").read_text(encoding="utf-8")

    assert re.search(r"ICEBERG_VERSION=([\d.]+)", sink).group(1) == re.search(r"<iceberg.version>([\d.]+)<", ROOT_POM).group(1)
