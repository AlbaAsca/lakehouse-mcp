"""T-004: comprobaciones del tester, independientes del generador y de test_synthetic.py.

La muestra se lee en crudo (sin módulo csv ni constantes del generador). Las cifras se contrastan con las que promete
infra/synthetic/README.md, de modo que ni los datos ni la documentación pueden cambiar sin que salte un test.
"""
import gzip
import importlib.util
import os
import re
import subprocess
import sys
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[1]
SAMPLE = ROOT / "testdata" / "synthetic-events.csv.gz"
HEADER = "event_time,event_type,product_id,category_id,category_code,brand,price,user_id,user_session"
FIELDS = HEADER.split(",")
UUID4 = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
TIME = re.compile(r"2024-03-(0[1-9]|1[0-4]) ([01]\d|2[0-3]):[0-5]\d:[0-5]\d UTC")
PRICE = re.compile(r"-?\d+\.\d{2}")
DROP_DAY, DROP_BRAND = "2024-03-10", "synth-nimbora"
# Lista parcial escrita de memoria, sin abrir el CSV real: marcas y categorías raíz habituales de ese dataset.
REAL_BRANDS = {"samsung", "apple", "xiaomi", "huawei", "lucente", "cordiant", "lg", "sony", "bosch", "oppo"}
REAL_ROOTS = {"electronics", "appliances", "computers", "furniture", "apparel", "construction", "auto", "kids"}


@pytest.fixture(scope="module")
def lines():
    text = gzip.decompress(SAMPLE.read_bytes()).decode("utf-8")
    assert text.endswith("\n")
    return text[:-1].split("\n")


@pytest.fixture(scope="module")
def rows(lines):
    return [dict(zip(FIELDS, line.split(","))) for line in lines[1:]]


def load_generator():
    spec = importlib.util.spec_from_file_location("synthetic_generate", ROOT / "infra" / "synthetic" / "generate.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def cents(price):
    negative = price.startswith("-")
    whole, decimals = price.lstrip("-").split(".")
    value = int(whole) * 100 + int(decimals)
    return -value if negative else value


# --- Fichero en crudo ---

def test_cabecera_y_numero_de_campos_en_crudo(lines):
    assert lines[0] == HEADER
    assert len(lines) == 50001
    assert [i for i, line in enumerate(lines) if line.count(",") != 8 or '"' in line] == []


def test_cada_campo_tiene_el_formato_del_origen(rows):
    for r in rows:
        assert TIME.fullmatch(r["event_time"]), r
        assert PRICE.fullmatch(r["price"]), r
        assert r["user_session"] == "" or UUID4.fullmatch(r["user_session"]), r


def test_ids_caben_en_enteros_con_signo(rows):
    # Los rangos propios son altos: category_id debe seguir cabiendo en 64 bits y product_id/user_id en 32.
    assert max(int(r["category_id"]) for r in rows) < 2 ** 63
    assert max(int(r["product_id"]) for r in rows) < 2 ** 31
    assert max(int(r["user_id"]) for r in rows) < 2 ** 31


def test_cabecera_gzip_sin_fecha_ni_nombre():
    raw = SAMPLE.read_bytes()
    assert raw[:2] == b"\x1f\x8b"
    assert raw[3] == 0, "sin FNAME ni otros campos opcionales"
    assert raw[4:8] == b"\0\0\0\0", "mtime=0"


def test_git_guarda_la_muestra_sin_tocar_sus_bytes():
    # .gitattributes tiene text=auto eol=lf: una conversión de saltos de línea rompería el gzip.
    blob = subprocess.run(["git", "cat-file", "blob", "HEAD:testdata/synthetic-events.csv.gz"],
                          cwd=ROOT, check=True, capture_output=True).stdout
    assert blob == SAMPLE.read_bytes()


# --- Cifras de casos sucios del README, una a una ---

def test_casos_sucios_coinciden_con_el_readme(rows, lines):
    assert sum(r["category_code"] == "" for r in rows) == 11952
    assert sum(r["brand"] == "" for r in rows) == 5029
    assert sum(r["user_session"] == "" for r in rows) == 50
    assert sum(cents(r["price"]) == 0 for r in rows) == 100
    assert sum(cents(r["price"]) < 0 for r in rows) == 102
    assert len(set(lines[1:])) == 49750


def test_precio_sucio_es_el_del_catalogo_alterado(rows):
    # Por producto hay un único precio limpio; los negativos son exactamente ese precio con el signo cambiado.
    clean = defaultdict(set)
    for r in rows:
        if cents(r["price"]) > 0:
            clean[r["product_id"]].add(cents(r["price"]))
    assert all(len(v) == 1 for v in clean.values())
    for r in rows:
        if cents(r["price"]) < 0:
            assert -cents(r["price"]) in clean[r["product_id"]], r


def test_filas_sin_sesion_conservan_su_paso_previo(rows):
    # Sin id de sesión, una compra o un carrito siguen teniendo su paso anterior: mismo usuario, producto y antes.
    previous = {"cart": "view", "purchase": "cart"}
    orphan = [r for r in rows if r["user_session"] == "" and r["event_type"] in previous]
    assert orphan, "la muestra debe incluir este caso"
    for r in orphan:
        assert any(o["user_id"] == r["user_id"] and o["product_id"] == r["product_id"]
                   and o["event_type"] == previous[r["event_type"]] and o["event_time"] <= r["event_time"]
                   for o in rows), r


def test_sesiones_duran_menos_de_una_hora(rows):
    times = defaultdict(list)
    for r in rows:
        if r["user_session"]:
            times[r["user_session"]].append(datetime.strptime(r["event_time"], "%Y-%m-%d %H:%M:%S UTC"))
    for session, values in times.items():
        assert max(values) - min(values) < timedelta(hours=1), session


# --- Caída plantada: cifras del README ---

def by_day(rows, event_type, keep):
    return Counter(r["event_time"][:10] for r in rows if r["event_type"] == event_type and keep(r))


def nimbora(r):
    return r["brand"] == DROP_BRAND


def test_tabla_de_la_caida_coincide_con_el_readme(rows):
    cases = [("purchase", nimbora, 10, 69, 104),
             ("purchase", lambda r: not nimbora(r), 140, 130, 183),
             ("purchase", lambda r: True, 150, 224, 263),
             ("view", nimbora, 966, 947, 1042),
             ("cart", nimbora, 184, 153, 196)]
    for event_type, keep, on_drop, low, high in cases:
        counts = by_day(rows, event_type, keep)
        assert len(counts) == 14
        others = [n for day, n in counts.items() if day != DROP_DAY]
        assert (counts[DROP_DAY], min(others), max(others)) == (on_drop, low, high), event_type


def test_la_caida_esta_en_el_paso_de_carrito_a_compra(rows):
    # Lo que el agente debe explicar: se añade igual al carrito, pero no se paga. Ratio frente a los otros días.
    carts, purchases = by_day(rows, "cart", nimbora), by_day(rows, "purchase", nimbora)
    assert purchases[DROP_DAY] / carts[DROP_DAY] < 0.10
    assert all(0.35 < purchases[d] / carts[d] < 0.70 for d in carts if d != DROP_DAY)


# --- Sin valores del dataset real ---

def test_ningun_valor_del_dataset_real(rows):
    brands = {r["brand"] for r in rows} - {""}
    assert not brands & REAL_BRANDS
    assert all(b.startswith("synth-") for b in brands)
    roots = {r["category_code"].split(".")[0] for r in rows} - {""}
    assert roots == {"synth"}
    assert not roots & REAL_ROOTS


def test_el_generador_solo_abre_su_fichero_de_salida(tmp_path):
    # Se observa la ejecución real con un gancho de auditoría: fuera de la instalación de Python, solo abre --out.
    out = tmp_path / "s.csv.gz"
    script = (
        "import importlib.util, os, sys\n"
        f"spec = importlib.util.spec_from_file_location('g', {str(ROOT / 'infra' / 'synthetic' / 'generate.py')!r})\n"
        "g = importlib.util.module_from_spec(spec); spec.loader.exec_module(g)\n"
        "opened = []\n"
        "sys.addaudithook(lambda e, a: opened.append(a[0]) if e == 'open' and isinstance(a[0], (str, os.PathLike)) else None)\n"
        f"g.main(['--rows', '1000', '--out', {str(out)!r}])\n"
        "base = os.path.realpath(sys.base_prefix)\n"
        "print('\\n'.join(sorted({os.path.realpath(p) for p in opened if not os.path.realpath(p).startswith(base)})))\n"
    )
    result = subprocess.run([sys.executable, "-c", script], cwd=tmp_path, check=True, capture_output=True, text=True)
    opened = [line for line in result.stdout.splitlines() if not line.startswith("1000 filas")]
    assert opened == [os.path.realpath(out)]


# --- Generador: casos hechos a mano y otros tamaños y semillas ---

@pytest.mark.parametrize("value, text", [(0, "0.00"), (5, "0.05"), (-5, "-0.05"), (100, "1.00"), (199, "1.99"),
                                         (-199, "-1.99"), (149999, "1499.99")])
def test_formato_de_precio(value, text):
    assert load_generator().format_price(value) == text


@pytest.mark.parametrize("seed, size", [(1, 1000), (2, 1001), (3, 1999), (7, 2500), (99, 7777), (0, 1000)])
def test_otros_tamanos_y_semillas_cumplen_el_contrato(seed, size):
    generated = load_generator().generate(seed, size)
    assert len(generated) == size
    assert all(len(row) == 9 for row in generated)
    assert [r[0] for r in generated] == sorted(r[0] for r in generated)
    assert any(r[4] == "" for r in generated) and any(r[5] == "" for r in generated)
    assert any(r[8] == "" for r in generated)
    assert any(cents(r[6]) == 0 for r in generated) and any(cents(r[6]) < 0 for r in generated)
    assert size - len(set(generated)) >= size // 200


def test_misma_semilla_mismas_filas_en_memoria():
    module = load_generator()
    assert module.generate(5, 2000) == module.generate(5, 2000)
    assert module.generate(5, 2000) != module.generate(6, 2000)
