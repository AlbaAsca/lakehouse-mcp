"""Muestra sintética de CI: se comprueba leyendo el CSV, sin reutilizar constantes del generador."""
import csv
import gzip
import io
import statistics
import subprocess
import sys
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[1]
GENERATOR = ROOT / "infra" / "synthetic" / "generate.py"
SAMPLE = ROOT / "testdata" / "synthetic-events.csv.gz"
COLUMNS = ["event_time", "event_type", "product_id", "category_id", "category_code",
           "brand", "price", "user_id", "user_session"]
MAX_CSV_BYTES = 5 * 1024 * 1024


def generate(out, seed=42, rows=5000):
    subprocess.run([sys.executable, str(GENERATOR), "--seed", str(seed), "--rows", str(rows), "--out", str(out)],
                   check=True, capture_output=True)
    return out


def read_rows(path):
    with gzip.open(path, "rt", encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


@pytest.fixture(scope="module")
def rows():
    return read_rows(SAMPLE)


def event_date(row):
    return date.fromisoformat(row["event_time"][:10])


def purchases_by_day(rows, keep):
    return Counter(event_date(r) for r in rows if r["event_type"] == "purchase" and keep(r))


# --- Determinismo ---

def test_dos_ejecuciones_dan_el_mismo_fichero_byte_a_byte(tmp_path):
    a = generate(tmp_path / "a.csv.gz")
    b = generate(tmp_path / "subdir" / "otro-nombre.csv.gz")
    assert a.read_bytes() == b.read_bytes()


def test_otra_semilla_da_otros_datos(tmp_path):
    a = generate(tmp_path / "a.csv.gz", seed=1)
    b = generate(tmp_path / "b.csv.gz", seed=2)
    assert a.read_bytes() != b.read_bytes()


def test_el_fichero_versionado_es_lo_que_genera_el_comando_documentado(tmp_path):
    # Se compara descomprimido: los bytes de gzip pueden variar con la versión de zlib de cada máquina.
    regenerated = generate(tmp_path / "s.csv.gz", seed=42, rows=50000)
    assert gzip.decompress(regenerated.read_bytes()) == gzip.decompress(SAMPLE.read_bytes())


def test_filas_de_menos_se_rechazan(tmp_path):
    result = subprocess.run([sys.executable, str(GENERATOR), "--rows", "10", "--out", str(tmp_path / "x.csv.gz")],
                            capture_output=True)
    assert result.returncode != 0
    assert not (tmp_path / "x.csv.gz").exists()


# --- Esquema ---

def test_esquema_y_numero_de_filas(rows):
    with gzip.open(SAMPLE, "rt", encoding="utf-8", newline="") as f:
        assert next(csv.reader(f)) == COLUMNS
    assert len(rows) == 50000


def test_tipos_de_cada_fila(rows):
    for r in rows:
        assert r["event_type"] in {"view", "cart", "purchase"}
        assert r["event_time"].endswith(" UTC") and len(r["event_time"]) == 23
        assert int(r["product_id"]) > 0 and int(r["category_id"]) > 0 and int(r["user_id"]) > 0
        float(r["price"])


def test_fichero_ordenado_y_catorce_dias(rows):
    times = [r["event_time"] for r in rows]
    assert times == sorted(times)
    assert len({event_date(r) for r in rows}) == 14
    assert event_date(rows[0]) == date(2024, 3, 1) and event_date(rows[-1]) == date(2024, 3, 14)


def test_sesiones_coherentes(rows):
    sessions = defaultdict(list)
    for r in rows:
        if r["user_session"]:
            sessions[r["user_session"]].append(r)
    assert len(sessions) > 1000
    funnel = Counter()
    for events in sessions.values():
        assert len({e["user_id"] for e in events}) == 1
        order = [e["event_type"] for e in events]
        assert order == sorted(order, key=["view", "cart", "purchase"].index)
        viewed = {e["product_id"] for e in events if e["event_type"] == "view"}
        carted = {e["product_id"] for e in events if e["event_type"] == "cart"}
        bought = {e["product_id"] for e in events if e["event_type"] == "purchase"}
        assert viewed, "toda sesión empieza por una vista"
        assert carted <= viewed and bought <= carted
        funnel.update(order)
    assert funnel["view"] > funnel["cart"] > funnel["purchase"] > 0


def test_cada_producto_tiene_una_sola_marca_y_categoria(rows):
    seen = defaultdict(set)
    for r in rows:
        seen[r["product_id"]].add((r["category_id"], r["category_code"], r["brand"]))
    assert all(len(v) == 1 for v in seen.values())


# --- Casos sucios ---

@pytest.mark.parametrize("column", ["category_code", "brand", "user_session"])
def test_hay_nulos_en(rows, column):
    assert any(r[column] == "" for r in rows)


def test_las_columnas_obligatorias_nunca_son_nulas(rows):
    for column in ("event_time", "event_type", "product_id", "category_id", "price", "user_id"):
        assert all(r[column] != "" for r in rows), column


def test_hay_precio_cero_y_negativo(rows):
    prices = [float(r["price"]) for r in rows]
    assert any(p == 0 for p in prices)
    assert any(p < 0 for p in prices)
    assert sum(p > 0 for p in prices) > 0.99 * len(prices)


def test_hay_duplicados_exactos(rows):
    copies = Counter(tuple(r.values()) for r in rows)
    assert sum(n - 1 for n in copies.values()) == 50000 // 200


def test_cada_caso_sucio_aparece_tambien_en_una_muestra_pequena(tmp_path):
    small = read_rows(generate(tmp_path / "s.csv.gz", rows=1000))
    assert len(small) == 1000
    for column in ("category_code", "brand", "user_session"):
        assert any(r[column] == "" for r in small), column
    assert any(float(r["price"]) == 0 for r in small)
    assert any(float(r["price"]) < 0 for r in small)
    assert len({tuple(r.values()) for r in small}) < 1000


# --- Caída plantada ---

DROP_DAY = date(2024, 3, 10)
DROP_BRAND = "synth-nimbora"


def test_caen_las_compras_de_la_marca_ese_dia(rows):
    counts = purchases_by_day(rows, lambda r: r["brand"] == DROP_BRAND)
    others = [counts[d] for d in counts if d != DROP_DAY]
    assert counts[DROP_DAY] < 0.3 * statistics.median(others)
    assert counts[DROP_DAY] < min(others), "ningún otro día se le parece"


def test_se_nota_en_el_total_del_dia(rows):
    counts = purchases_by_day(rows, lambda r: True)
    others = [counts[d] for d in counts if d != DROP_DAY]
    assert counts[DROP_DAY] < min(others)


def test_las_demas_marcas_compran_con_normalidad_ese_dia(rows):
    counts = purchases_by_day(rows, lambda r: r["brand"] != DROP_BRAND)
    others = [counts[d] for d in counts if d != DROP_DAY]
    assert min(others) <= counts[DROP_DAY] <= max(others)


@pytest.mark.parametrize("event_type", ["view", "cart"])
def test_vistas_y_carritos_de_la_marca_no_caen(rows, event_type):
    counts = Counter(event_date(r) for r in rows if r["event_type"] == event_type and r["brand"] == DROP_BRAND)
    others = [counts[d] for d in counts if d != DROP_DAY]
    assert counts[DROP_DAY] > 0.75 * statistics.median(others)


# --- Sin valores del dataset real ---

def test_valores_en_rangos_propios(rows):
    for r in rows:
        assert int(r["product_id"]) > 900_000_000
        assert int(r["user_id"]) > 900_000_000
        assert int(r["category_id"]) > 9_000_000_000_000_000_000
        assert r["event_time"].startswith("2024-03-"), "fuera del periodo del dataset real"
        assert r["brand"] == "" or r["brand"].startswith("synth-")
        assert r["category_code"] == "" or r["category_code"].startswith("synth.")


def test_el_generador_solo_usa_biblioteca_estandar():
    source = GENERATOR.read_text(encoding="utf-8")
    imports = {line.split()[1].split(".")[0] for line in source.splitlines() if line.startswith(("import ", "from "))}
    assert imports <= set(sys.stdlib_module_names)


# --- Guarda del repo ---

def csv_infractions(root, paths):
    """CSV sin comprimir, o CSV comprimido de más de 5 MB, entre los ficheros indicados."""
    bad = []
    for path in paths:
        name = path.lower()
        if name.endswith(".csv"):
            bad.append(f"{path}: CSV sin comprimir")
        elif ".csv." in name and (root / path).stat().st_size > MAX_CSV_BYTES:
            bad.append(f"{path}: más de 5 MB")
    return bad


def test_la_guarda_detecta_lo_prohibido(tmp_path):
    (tmp_path / "real.csv").write_text("a,b\n", encoding="utf-8")
    (tmp_path / "PEQUE.CSV").write_text("a,b\n", encoding="utf-8")
    with open(tmp_path / "grande.csv.gz", "wb") as f:
        f.truncate(MAX_CSV_BYTES + 1)
    with open(tmp_path / "bien.csv.gz", "wb") as f:
        f.truncate(MAX_CSV_BYTES)
    (tmp_path / "notas.md").write_text("x", encoding="utf-8")
    found = csv_infractions(tmp_path, ["real.csv", "PEQUE.CSV", "grande.csv.gz", "bien.csv.gz", "notas.md"])
    assert sorted(f.split(":")[0] for f in found) == ["PEQUE.CSV", "grande.csv.gz", "real.csv"]


def test_el_repo_no_lleva_csv_prohibidos():
    # Versionados y los nuevos que se añadirían; los ignorados (data/ con el CSV real) no suben.
    listed = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                            cwd=ROOT, check=True, capture_output=True).stdout.decode("utf-8")
    paths = [p for p in listed.split("\0") if p and (ROOT / p).exists()]
    assert csv_infractions(ROOT, paths) == []
    assert "testdata/synthetic-events.csv.gz" in paths, "la muestra debe poder versionarse (.gitignore)"
    assert SAMPLE.stat().st_size < MAX_CSV_BYTES


def test_el_csv_real_sigue_ignorado():
    ignored = subprocess.run(["git", "check-ignore", "-q", "data/events.csv"], cwd=ROOT)
    assert ignored.returncode == 0


def test_la_muestra_es_texto_utf8():
    text = io.TextIOWrapper(gzip.open(SAMPLE, "rb"), encoding="utf-8", newline="").read()
    assert "\r" not in text and text.endswith("\n")
