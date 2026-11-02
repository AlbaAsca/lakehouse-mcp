"""T-002: compose.yaml must keep the persistence that testkit/PolarisPersistenceIT proves.

The integration test builds its containers from its own copy of the configuration, so it stays green
if compose.yaml loses the persistence. These checks tie both together. Plain text on purpose: the
tools image has no YAML parser.
"""
import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
COMPOSE = (ROOT / "compose.yaml").read_text(encoding="utf-8")
IT = (ROOT / "testkit/src/test/java/io/github/albaasca/lakehouse/testkit/PolarisPersistenceIT.java").read_text(
    encoding="utf-8"
)
EXPECTED = {
    "polaris.persistence.type": "relational-jdbc",
    "quarkus.datasource.username": "polaris",
    "quarkus.datasource.jdbc.url": "jdbc:postgresql://postgres:5432/polaris",
}


def service(compose, name):
    match = re.search(rf"^  {name}:\n(.*?)(?=^  \S|^\S|\Z)", compose, re.M | re.S)
    return match.group(1) if match else ""


def anchor(compose):
    match = re.search(r"^x-polaris-persistence: &polaris-persistence\n((?:  .+\n)+)", compose, re.M)
    return dict(line.strip().split(": ", 1) for line in match.group(1).splitlines()) if match else {}


def it_persistence(source):
    return dict(re.findall(r'"([a-z.-]+)",\s*"([^"]+)"', source[source.index("PERSISTENCE = Map.of(") :].split(";")[0]))


def problems(compose, it):
    """Every way compose.yaml can drift from the configuration proven by the integration test."""
    found = []
    block = anchor(compose)
    proven = it_persistence(it)
    for key, value in EXPECTED.items():
        if block.get(key) != value:
            found.append(f"compose {key}={block.get(key)}")
        if proven.get(key) != value:
            found.append(f"test {key}={proven.get(key)}")
    if not block.get("quarkus.datasource.password", "").startswith("${POSTGRES_PASSWORD"):
        found.append("compose password does not come from POSTGRES_PASSWORD")

    polaris = service(compose, "polaris")
    bootstrap = service(compose, "polaris-bootstrap")
    postgres = service(compose, "postgres")
    if "<<: *polaris-persistence" not in polaris:
        found.append("polaris does not use the shared persistence")
    if "environment: *polaris-persistence" not in bootstrap:
        found.append("polaris-bootstrap does not use the shared persistence")
    if "polaris-bootstrap: {condition: service_completed_successfully}" not in polaris:
        found.append("polaris does not wait for the bootstrap")
    if "POLARIS_BOOTSTRAP_CREDENTIALS" in compose:
        found.append("in-memory bootstrap credentials are back")
    # Postgres 18 keeps its data in a versioned folder under this path: a mount on .../data would lose it.
    if "postgres-data:/var/lib/postgresql]" not in postgres:
        found.append("postgres data is not on the named volume")
    if not re.search(r"^  postgres-data:$", compose, re.M):
        found.append("postgres-data volume is not declared")

    for image in ("apache/polaris:", "apache/polaris-admin-tool:"):
        ours = re.search(rf"image: ({re.escape(image)}\S+)", compose)
        if not ours or f'"{ours.group(1)}"' not in it:
            found.append(f"{image} differs between compose and the test")
    return found


def test_compose_matches_the_proven_configuration():
    assert problems(COMPOSE, IT) == []


@pytest.mark.parametrize(
    "old, new",
    [
        ("polaris.persistence.type: relational-jdbc", "polaris.persistence.type: in-memory"),
        ("jdbc:postgresql://postgres:5432/polaris", "jdbc:postgresql://postgres:5432/other"),
        ("quarkus.datasource.password: ${POSTGRES_PASSWORD:-change-me-postgres}", "quarkus.datasource.password: x"),
        ("      <<: *polaris-persistence\n", ""),
        ("environment: *polaris-persistence", "environment: {}"),
        ("      polaris-bootstrap: {condition: service_completed_successfully}\n", ""),
        ("      polaris.realm-context.realms:", "      POLARIS_BOOTSTRAP_CREDENTIALS: a,b,c\n      polaris.realm-context.realms:"),
        ("postgres-data:/var/lib/postgresql]", "postgres-data:/var/lib/postgresql/data]"),
        ("  postgres-data:\n", ""),
        ("apache/polaris:1.8.0", "apache/polaris:1.7.0"),
        ("apache/polaris-admin-tool:1.8.0", "apache/polaris-admin-tool:1.7.0"),
    ],
)
def test_each_drift_is_detected(old, new):
    assert old in COMPOSE
    assert problems(COMPOSE.replace(old, new), IT) != []


def test_test_copy_drift_is_detected():
    assert problems(COMPOSE, IT.replace('"relational-jdbc"', '"in-memory"')) != []


def test_postgres_image_creates_what_polaris_needs():
    dockerfile = (ROOT / "infra/postgres/Dockerfile").read_text(encoding="utf-8")
    init_sql = (ROOT / "infra/postgres/init.sql").read_text(encoding="utf-8")
    assert "COPY init.sql /docker-entrypoint-initdb.d/" in dockerfile
    assert "CREATE SCHEMA polaris_schema;" in init_sql
    assert "CREATE DATABASE ops;" in init_sql


def test_env_example_lists_the_postgres_password():
    assert re.search(r"^POSTGRES_PASSWORD=", (ROOT / ".env.example").read_text(encoding="utf-8"), re.M)
