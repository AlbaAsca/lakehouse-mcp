"""T-092: la cifra de referencia de la demo, contra un caso hecho a mano y contra un recálculo en crudo de la muestra."""
import gzip
import importlib.util
import json
import subprocess
import sys
from collections import defaultdict
from fractions import Fraction
from pathlib import Path

ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "evals" / "demo" / "reference.py"
EXPECTED = ROOT / "evals" / "demo" / "expected.json"
SAMPLE = ROOT / "testdata" / "synthetic-events.csv.gz"
HEADER = "event_time,event_type,product_id,category_id,category_code,brand,price,user_id,user_session"

spec = importlib.util.spec_from_file_location("demo_reference", SCRIPT)
reference = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reference)


def write_sample(path, events):
    """events: (event_type, brand, price). El resto de columnas no influye."""
    lines = [HEADER] + [f"2024-03-01 00:00:00 UTC,{t},1,2,a.b,{b},{p},3,s" for t, b, p in events]
    path.write_bytes(gzip.compress(("\n".join(lines) + "\n").encode("utf-8")))
    return path


def top(result):
    return [(item["brand"], item["revenue"]) for item in result["top_brands_by_purchase_revenue"]]


def test_caso_a_mano(tmp_path):
    sample = write_sample(tmp_path / "s.csv.gz", [
        ("purchase", "acme", "10.10"),
        ("purchase", "acme", "10.10"),  # duplicado exacto: cuenta dos veces
        ("purchase", "acme", "0.10"),
        ("purchase", "beta", "30.00"),
        ("purchase", "beta", "-5.00"),  # negativo: resta
        ("purchase", "", "999.00"),  # sin marca: fuera del ranking, pero es una fila
        ("view", "gamma", "1000.00"),  # no es compra
        ("cart", "gamma", "1000.00"),  # no es compra
        ("purchase", "delta", "0.00"),
    ])
    result = reference.compute(sample)
    assert result["total_rows"] == 9
    assert top(result) == [("beta", "25.00"), ("acme", "20.30"), ("delta", "0.00")]


def test_suma_exacta_sin_error_de_coma_flotante(tmp_path):
    sample = write_sample(tmp_path / "s.csv.gz", [("purchase", "acme", "0.10")] * 3)
    assert top(reference.compute(sample)) == [("acme", "0.30")]


def test_solo_cinco_marcas_y_empates_por_nombre(tmp_path):
    sample = write_sample(tmp_path / "s.csv.gz", [("purchase", b, "1.00") for b in "fedcba"])
    assert [brand for brand, _ in top(reference.compute(sample))] == list("abcde")


def test_dos_ejecuciones_dan_el_mismo_fichero(tmp_path):
    outputs = [tmp_path / "a.json", tmp_path / "b.json"]
    for output in outputs:
        subprocess.run([sys.executable, str(SCRIPT), "--output", str(output)], check=True, capture_output=True)
    assert outputs[0].read_bytes() == outputs[1].read_bytes()


def test_el_fichero_subido_es_lo_que_sale_de_recalcular():
    assert EXPECTED.read_text(encoding="utf-8") == reference.render(reference.compute(SAMPLE))  # tolera CRLF de git


def test_las_reglas_estan_documentadas_en_el_fichero():
    rules = " ".join(json.loads(EXPECTED.read_text(encoding="utf-8"))["rules"])
    assert "duplicados" in rules and "marca vacía" in rules


def test_elemento_a_elemento_contra_recalculo_en_crudo():
    """Otra vía: líneas partidas a mano y fracciones, sin csv ni Decimal. La muestra no lleva comas entre comillas."""
    lines = gzip.decompress(SAMPLE.read_bytes()).decode("utf-8").splitlines()
    assert lines[0] == HEADER
    assert '"' not in "".join(lines)
    revenue = defaultdict(Fraction)
    for line in lines[1:]:
        fields = line.split(",")
        assert len(fields) == 9
        if fields[1] == "purchase" and fields[5] != "":
            revenue[fields[5]] += Fraction(fields[6])
    raw = sorted(revenue.items(), key=lambda item: (-item[1], item[0]))[:5]

    expected = json.loads(EXPECTED.read_text(encoding="utf-8"))
    assert expected["total_rows"] == len(lines) - 1
    assert [(i["brand"], Fraction(i["revenue"])) for i in expected["top_brands_by_purchase_revenue"]] == raw
