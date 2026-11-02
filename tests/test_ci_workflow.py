"""T-009: the CI workflow must keep running the whole suite with no secrets and no Kaggle.

GitHub is the only place that really runs the workflow; these checks stop the file from silently
losing what the card asks for. Plain text on purpose: the CI job does not install a YAML parser.
"""
import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
CI = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")


def code(workflow):
    """Workflow without comments, which may mention anything."""
    return "\n".join(line.split(" #")[0] for line in workflow.splitlines() if not line.lstrip().startswith("#"))


def job(workflow, name):
    match = re.search(rf"^  {name}:\n(.*?)(?=^  \S|\Z)", code(workflow), re.M | re.S)
    return match.group(1) if match else ""


def on_block(workflow):
    match = re.search(r"^on:\n((?:  .*\n|\n)+)", code(workflow) + "\n", re.M)
    return match.group(1) if match else ""


def push_branches(workflow):
    match = re.search(r"push:\n\s+branches: \[(.*?)\]", on_block(workflow))
    return {branch.strip() for branch in match.group(1).split(",")} if match else set()


def forbidden(workflow):
    """Everything the workflow must not depend on: the card demands no secrets and no Kaggle."""
    text = code(workflow)
    found = re.findall(r"\$\{\{\s*secrets\.\w+", text)
    found += re.findall(r"kaggle", text, re.I)
    return found


def timeout(job_text):
    match = re.search(r"^    timeout-minutes: (\d+)$", job_text, re.M)
    return int(match.group(1)) if match else None


def test_runs_on_every_push_to_develop_and_main_and_on_pull_requests():
    assert push_branches(CI) == {"develop", "main"}
    assert re.search(r"^  pull_request:", on_block(CI), re.M)


def test_needs_no_secrets_and_no_kaggle():
    assert forbidden(CI) == []


def test_token_is_read_only():
    assert re.search(r"^permissions:\n  contents: read$", CI, re.M)


def test_java_job_runs_the_whole_build_on_jdk_25():
    java = job(CI, "java")
    assert "distribution: temurin" in java
    assert 'java-version: "25"' in java
    assert "cache: maven" in java
    assert re.search(r"run: mvn -B -ntp verify$", java, re.M)


def test_no_test_is_skipped_by_the_build_command():
    assert not re.search(r"skip|-Dtest=|-pl\b|-Dit\.test|--fail-never|-fae|-Dmaven\.test\.failure", code(CI), re.I)
    assert "continue-on-error" not in code(CI)


def test_python_job_runs_the_whole_tests_folder():
    assert re.search(r"run: python -m pytest tests -q$", job(CI, "board-and-scripts"), re.M)


@pytest.mark.parametrize("name", ["java", "board-and-scripts"])
def test_every_job_has_a_timeout_and_a_pinned_runner(name):
    text = job(CI, name)
    assert timeout(text) is not None
    assert re.search(r"runs-on: ubuntu-\d\d\.\d\d$", text, re.M)


def test_wiki_documents_the_same_jobs_and_timeouts():
    wiki = (ROOT / "docs/wiki/04-operacion.md").read_text(encoding="utf-8")
    for name in ("java", "board-and-scripts"):
        assert re.search(rf"^\| `{name}` \|.*\| {timeout(job(CI, name))} min \|$", wiki, re.M)


# The helpers above must really detect a workflow that lost what the card asks for.
@pytest.mark.parametrize(
    "broken",
    [
        CI.replace("branches: [develop, main]", "branches: [main]"),
        CI.replace("  pull_request:\n", ""),
    ],
)
def test_detects_lost_triggers(broken):
    assert push_branches(broken) != {"develop", "main"} or not re.search(r"^  pull_request:", on_block(broken), re.M)


def test_detects_a_secret_or_kaggle():
    assert forbidden(CI + "      - run: echo ${{ secrets.KAGGLE_KEY }}\n")
    assert forbidden(CI + "      - run: kaggle datasets download x\n")


def test_comments_do_not_count_as_dependencies():
    assert forbidden(CI + "      # kaggle is not used\n") == []
