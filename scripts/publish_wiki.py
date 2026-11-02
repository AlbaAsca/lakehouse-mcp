"""Convierte docs/wiki a la estructura plana de la Wiki de GitHub. docs/wiki es la única fuente: la Wiki se regenera entera.
Uso: python scripts/publish_wiki.py <carpeta de salida> [--repo owner/name] [--branch main]
"""
import argparse
import posixpath
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LINK = re.compile(r"(!?)\[([^\]]*)\]\(([^)\s]+)\)")
SKIP = ("http://", "https://", "mailto:", "#")


def page_name(rel):
    """README.md → Home; 01-conceptos.md → Conceptos; componentes/kafka-connect.md → Kafka-Connect."""
    if rel == "README.md":
        return "Home"
    stem = re.sub(r"^\d+-", "", posixpath.basename(rel)[:-3])
    return "-".join(w.capitalize() for w in stem.split("-"))


def rewrite(text, rel, pages, repo_root, wiki_prefix, repo, branch):
    """Reescribe los enlaces relativos de una página; un destino que no existe es un error, no un enlace roto publicado."""
    def sub(m):
        bang, label, target = m.groups()
        if target.startswith(SKIP):
            return m.group(0)
        path, _, anchor = target.partition("#")
        full = posixpath.normpath(posixpath.join(wiki_prefix, posixpath.dirname(rel), path))
        if full.startswith(".."):
            raise ValueError(f"{rel}: enlace fuera del repositorio: {target}")
        if not (repo_root / full).exists():
            raise ValueError(f"{rel}: enlace roto: {target}")
        inner = posixpath.relpath(full, wiki_prefix)
        if inner in pages:
            url = pages[inner]
        else:
            kind = "raw" if bang else "blob"
            url = f"https://github.com/{repo}/{kind}/{branch}/{full}"
        return f"{bang}[{label}]({url}{'#' + anchor if anchor else ''})"

    out, fenced = [], False
    for line in text.split("\n"):
        if line.lstrip().startswith("```"):
            fenced = not fenced
        out.append(line if fenced else LINK.sub(sub, line))
    return "\n".join(out)


def title(text, fallback):
    m = re.search(r"^# (.+)$", text, flags=re.M)
    return m.group(1).strip() if m else fallback


def build(wiki_dir, out_dir, repo_root, repo, branch):
    """Escribe las páginas, la barra lateral y el pie, y borra las páginas que ya no existen en docs/wiki."""
    wiki_dir, out_dir = Path(wiki_dir), Path(out_dir)
    files = sorted(p.relative_to(wiki_dir).as_posix() for p in wiki_dir.rglob("*.md"))
    pages = {rel: page_name(rel) for rel in files}
    if len(set(pages.values())) != len(pages):
        raise ValueError(f"nombres de página repetidos: {sorted(pages.values())}")
    prefix = wiki_dir.relative_to(repo_root).as_posix()
    for old in out_dir.glob("*.md"):
        old.unlink()

    guides, components = [], []
    for rel in files:
        text = (wiki_dir / rel).read_text(encoding="utf-8")
        (out_dir / f"{pages[rel]}.md").write_text(rewrite(text, rel, pages, Path(repo_root), prefix, repo, branch),
                                                  encoding="utf-8", newline="\n")
        if rel != "README.md":
            (components if "/" in rel else guides).append(f"- [{title(text, pages[rel])}]({pages[rel]})")
    sidebar = ["**[Inicio](Home)**", "", "**Guías**", "", *guides, "", "**Componentes**", "", *components, ""]
    (out_dir / "_Sidebar.md").write_text("\n".join(sidebar), encoding="utf-8", newline="\n")
    (out_dir / "_Footer.md").write_text(
        f"Generada desde [`docs/wiki`](https://github.com/{repo}/tree/{branch}/docs/wiki). "
        "Los cambios se hacen allí: lo que se edite aquí se sobrescribe.\n", encoding="utf-8", newline="\n")
    return pages


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--repo", default="AlbaAsca/lakehouse-mcp")
    ap.add_argument("--branch", default="main")
    o = ap.parse_args(argv)
    print(len(build(ROOT / "docs" / "wiki", o.out, ROOT, o.repo, o.branch)), "páginas")


if __name__ == "__main__":
    main()
