"""mk.cmd conserva el código de salida de docker compose (T-133).

Sin Docker real: en un directorio temporal se pone una copia de mk.cmd y un
«docker.exe» que es una copia del intérprete de Python. mk.cmd lanza
`docker compose run ...`, así que el intérprete ejecuta el script `compose`
del mismo directorio, que sale con el código de FAKE_RC. Tiene que ser un .exe:
un docker.cmd no volvería a mk.cmd (los .cmd encadenados sin `call` no regresan).
"""
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]

pytestmark = pytest.mark.skipif(sys.platform != "win32", reason="mk.cmd solo corre en Windows")


@pytest.fixture
def fake_repo(tmp_path):
    shutil.copyfile(ROOT / "mk.cmd", tmp_path / "mk.cmd")
    base = Path(sys.base_prefix)
    shutil.copyfile(base / "python.exe", tmp_path / "docker.exe")
    (tmp_path / "compose").write_text(
        "import os, sys\nsys.exit(int(os.environ['FAKE_RC']))\n", encoding="utf-8")
    return tmp_path


def run_mk(repo, rc):
    base = sys.base_prefix
    env = {**os.environ, "FAKE_RC": str(rc), "PYTHONHOME": base,
           "PATH": os.pathsep.join([str(repo), base, os.environ.get("PATH", "")])}
    env.pop("PYTHONPATH", None)
    return subprocess.run(["cmd", "/c", str(repo / "mk.cmd"), "test", "MODULE=noexiste"],
                          cwd=repo, env=env, capture_output=True, text=True, timeout=60)


@pytest.mark.parametrize("rc", [0, 2])
def test_mk_cmd_devuelve_el_codigo_de_docker(fake_repo, rc):
    res = run_mk(fake_repo, rc)
    assert res.returncode == rc, res.stdout + res.stderr
