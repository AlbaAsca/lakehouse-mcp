"""T-096: `make ask` runs the agent service; AgentImageIT tests the image with its context written by hand.

These checks tie the service, the image test, the Dockerfile and the root .dockerignore together, and run the make
target with a fake docker to see the question arrive as one argument, quotes and all.
"""
import os
import re
import shutil
import stat
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
IT = (ROOT / "agent/src/test/java/io/github/albaasca/lakehouse/agent/AgentImageIT.java").read_text(encoding="utf-8")
DOCKERFILE = (ROOT / "infra/agent/Dockerfile").read_text(encoding="utf-8")
ENTRYPOINT = (ROOT / "infra/agent/ask.sh").read_text(encoding="utf-8")
ENV_EXAMPLE = (ROOT / ".env.example").read_text(encoding="utf-8")
COMPOSE = (ROOT / "compose.yaml").read_text(encoding="utf-8")
DOCKERIGNORE = (ROOT / ".dockerignore").read_text(encoding="utf-8")
ROOT_POM = (ROOT / "pom.xml").read_text(encoding="utf-8")


def service():
    match = re.search(r"^  agent:\n((?:(?:    .*)?\n)+?)(?=^  \S|^\S)", COMPOSE, re.MULTILINE)
    assert match, "agent is not a service of compose.yaml"
    return match.group(1)


def compose_environment():
    block = re.search(r"^    environment:\n((?:      .*\n)+)", service(), re.MULTILINE)
    assert block, "agent has no environment block"
    return dict(re.findall(r"^      (\w+): (.+)$", block.group(1), re.MULTILINE))


def dockerfile_sources():
    sources = set()
    for line in DOCKERFILE.splitlines():
        if line.startswith("COPY ") and "--from=" not in line:
            sources.update(line.split()[1:-1])
    return sources


def test_it_only_runs_when_asked_within_its_budget():
    assert re.findall(r"^    profiles: (.+)$", service(), re.MULTILINE) == ["[agent]"]
    assert 'restart: "no"' in service()
    assert "    mem_limit: 1g\n" in service()
    assert "ports:" not in service()
    assert "volumes:" not in service()
    assert "      mcp-server: {condition: service_healthy}\n" in service()


def test_the_keys_and_model_come_from_the_env_and_may_be_empty():
    env = compose_environment()

    assert env.pop("MCP_SERVER_URL") == "http://mcp-server:8090"
    assert set(env) == {"MCP_ANALYST_KEY", "AGENT_PROVIDER", "AGENT_MODEL", "GEMINI_API_KEY", "ANTHROPIC_API_KEY"}
    for name, value in env.items():
        # With :- compose passes an empty value instead of warning, and the entrypoint says what is missing.
        assert value.startswith(f"${{{name}:-"), name
        assert name in ENV_EXAMPLE, f"{name} is not in .env.example"


def test_the_entrypoint_checks_the_key_of_each_provider():
    assert 'google-genai) name=GEMINI_API_KEY; key="$GEMINI_API_KEY"' in ENTRYPOINT
    assert 'name=ANTHROPIC_API_KEY; key="$ANTHROPIC_API_KEY"' in ENTRYPOINT
    assert "AGENT_MODEL is missing in .env" in ENTRYPOINT
    assert '"$name is missing in .env' in ENTRYPOINT
    assert 'exec java -Xmx512m -jar /app/agent.jar "$@"' in ENTRYPOINT


def run_entrypoint(tmp_path, **env):
    """ask.sh with a fake java that prints whether AGENT_MODEL reaches it, and what."""
    sh = shutil.which("sh")
    if sh is None:
        pytest.skip("needs a POSIX shell")
    java = tmp_path / "java"
    java.write_text('#!/bin/sh\necho "model=${AGENT_MODEL-unset}"\n', encoding="utf-8", newline="\n")
    java.chmod(0o755)
    base = {k: v for k, v in os.environ.items() if not k.startswith(("AGENT_", "GEMINI_", "ANTHROPIC_"))}
    full = {**base, "PATH": f"{tmp_path}{os.pathsep}{os.environ['PATH']}", **env}
    return subprocess.run([sh, str(ROOT / "infra/agent/ask.sh"), "q"], env=full, capture_output=True, text=True,
                          timeout=60)


def test_an_empty_model_is_dropped_so_spring_uses_the_default(tmp_path):
    # T-172: compose passes AGENT_MODEL="" when .env lacks it; Spring would take "" and Gemini fail with "model is required".
    out = run_entrypoint(tmp_path, GEMINI_API_KEY="k", AGENT_MODEL="")

    assert out.returncode == 0, out.stderr
    assert out.stdout.strip() == "model=unset"


def test_a_set_model_reaches_java(tmp_path):
    out = run_entrypoint(tmp_path, GEMINI_API_KEY="k", AGENT_MODEL="gemini-x")

    assert out.stdout.strip() == "model=gemini-x"


def test_claude_still_needs_a_model(tmp_path):
    out = run_entrypoint(tmp_path, AGENT_PROVIDER="anthropic", ANTHROPIC_API_KEY="k", AGENT_MODEL="")

    assert out.returncode == 5
    assert "AGENT_MODEL is missing in .env" in out.stderr


def test_the_guide_matches_the_exit_codes_of_the_image_and_of_mk_ask():
    # T-165: image without key exits 5 (AgentImageIT), ./mk ask exits 2 (make).
    ops = (ROOT / "docs/wiki/04-operacion.md").read_text(encoding="utf-8")
    comp = (ROOT / "docs/wiki/componentes/agente.md").read_text(encoding="utf-8")

    assert "isEqualTo(5)" in IT
    assert "termina con código 5, pero `./mk ask` devuelve 2" in ops
    assert "la imagen termina con código 5" in comp
    assert "sale con 2" in comp
    assert "termina con código 2" not in ops + comp


def test_the_image_test_sends_what_the_dockerfile_copies():
    block = re.search(r"CONTEXT = List\.of\((.*?)\);", IT, re.DOTALL)
    assert block, "AgentImageIT no longer lists its build context"
    context = set(re.findall(r'"([^"]+)"', block.group(1)))

    assert {p for p in context if p.endswith("pom.xml")} == {s for s in dockerfile_sources() if s.endswith("pom.xml")}
    assert {"agent/src/main", "testkit/src/main", "infra/agent"} <= context
    modules = re.findall(r"<module>(\w[\w-]*)</module>", ROOT_POM)
    assert {f"{module}/pom.xml" for module in modules} <= dockerfile_sources()


def test_the_dockerignore_lets_the_agent_through_and_nothing_secret():
    allowed = {line[1:] for line in DOCKERIGNORE.splitlines() if line.startswith("!")}

    assert {"agent/src/main", "infra/agent", "testkit/src/main"} <= allowed
    assert not {".env", ".git"} & allowed


@pytest.mark.skipif(shutil.which("make") is None or os.name == "nt", reason="make runs in the tools container")
@pytest.mark.parametrize("question", [
    "How many rows does the events table have?",
    "Which brand's \"view\" events  doubled?",
    # Not $: make expands it before the shell sees the question (documented in docs/wiki/04-operacion.md).
    "it's; `not` a command | at all & 50%",
])
def test_the_question_reaches_docker_as_one_argument(tmp_path, question):
    fake = tmp_path / "docker"
    fake.write_text('#!/bin/sh\nfor arg in "$@"; do printf "%s\\0" "$arg"; done\n', encoding="utf-8")
    fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
    env = {**os.environ, "PATH": f"{tmp_path}{os.pathsep}{os.environ['PATH']}"}

    out = subprocess.run(["make", "-s", "-C", str(ROOT), "ask", f"Q={question}"], env=env, capture_output=True, check=True)

    assert out.stdout.split(b"\0")[:-1] == [b"compose", b"run", b"--rm", b"--build", b"agent", b"ask", question.encode()]


@pytest.mark.skipif(shutil.which("make") is None or os.name == "nt", reason="make runs in the tools container")
def test_without_a_question_it_says_how_to_ask():
    out = subprocess.run(["make", "-s", "-C", str(ROOT), "ask"], capture_output=True, text=True)

    assert out.returncode != 0
    assert 'Usage: ./mk ask Q="your question"' in out.stderr
