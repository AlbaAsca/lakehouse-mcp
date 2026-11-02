"""Conversión de docs/wiki a la Wiki de GitHub: enlaces reescritos, código intacto y ningún enlace roto publicado."""
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import publish_wiki as pw  # noqa: E402


@pytest.fixture
def repo(tmp_path):
    w = tmp_path / "docs" / "wiki"
    (w / "componentes").mkdir(parents=True)
    (tmp_path / "docs" / "img").mkdir()
    (tmp_path / "docs" / "img" / "a.svg").write_text("<svg/>")
    (tmp_path / "compose.yaml").write_text("x")
    (w / "README.md").write_text("# Portada\n[c](01-conceptos.md) [k](componentes/kafka-connect.md#fallos)\n", encoding="utf-8")
    (w / "01-conceptos.md").write_text("# Conceptos\n![i](../img/a.svg) [web](https://x.io) [aqui](#ancla)\n"
                                       "```\n[no](01-conceptos.md)\n```\n", encoding="utf-8")
    (w / "componentes" / "kafka-connect.md").write_text("# Connect\n[p](../README.md) [f](../../../compose.yaml)\n", encoding="utf-8")
    return tmp_path


def build(repo, tmp_path_out):
    tmp_path_out.mkdir(exist_ok=True)
    pw.build(repo / "docs" / "wiki", tmp_path_out, repo, "o/r", "main")
    return tmp_path_out


def test_nombres_de_pagina():
    assert pw.page_name("README.md") == "Home"
    assert pw.page_name("03-probarlo.md") == "Probarlo"
    assert pw.page_name("componentes/kafka-connect.md") == "Kafka-Connect"


def test_reescribe_enlaces_y_respeta_el_codigo(repo):
    out = build(repo, repo / "out")
    assert "[c](Conceptos) [k](Kafka-Connect#fallos)" in (out / "Home.md").read_text(encoding="utf-8")
    con = (out / "Conceptos.md").read_text(encoding="utf-8")
    assert "![i](https://github.com/o/r/raw/main/docs/img/a.svg)" in con
    assert "[web](https://x.io) [aqui](#ancla)" in con
    assert "[no](01-conceptos.md)" in con
    connect = (out / "Kafka-Connect.md").read_text(encoding="utf-8")
    assert "[p](Home) [f](https://github.com/o/r/blob/main/compose.yaml)" in connect


def test_barra_lateral_y_borra_paginas_viejas(repo):
    (repo / "out").mkdir()
    (repo / "out" / "Vieja.md").write_text("x")
    out = build(repo, repo / "out")
    assert not (out / "Vieja.md").exists()
    side = (out / "_Sidebar.md").read_text(encoding="utf-8")
    assert "- [Conceptos](Conceptos)" in side and "- [Connect](Kafka-Connect)" in side
    assert "Home" in side


def test_un_enlace_roto_no_se_publica(repo):
    (repo / "docs" / "wiki" / "01-conceptos.md").write_text("[x](no-existe.md)", encoding="utf-8")
    with pytest.raises(ValueError, match="enlace roto"):
        build(repo, repo / "out")


def test_la_wiki_real_se_convierte(tmp_path):
    pages = pw.build(ROOT / "docs" / "wiki", tmp_path, ROOT, "o/r", "main")
    assert pages["README.md"] == "Home" and (tmp_path / "Home.md").exists()
    names = {p.stem for p in tmp_path.glob("*.md")}
    for p in tmp_path.glob("*.md"):
        fenced = False
        for line in p.read_text(encoding="utf-8").split("\n"):
            if line.lstrip().startswith("```"):
                fenced = not fenced
            if fenced:
                continue
            for _, _, target in pw.LINK.findall(line):
                if not target.startswith(pw.SKIP):
                    assert target.partition("#")[0] in names, (p.name, target)
