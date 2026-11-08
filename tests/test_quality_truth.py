"""T-023: la verdad de referencia de calidad, contra un caso a mano, la muestra documentada y un recálculo en crudo."""
import gzip
import importlib.util
import json
import subprocess
import sys
from collections import Counter
from fractions import Fraction
from pathlib import Path

ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "evals" / "truth" / "compute_quality_truth.py"
QUALITY = ROOT / "evals" / "truth" / "quality.json"
SAMPLE = ROOT / "testdata" / "synthetic-events.csv.gz"
HEADER = "event_time,event_type,product_id,category_id,category_code,brand,price,user_id,user_session"
COLUMNS = HEADER.split(",")

spec = importlib.util.spec_from_file_location("quality_truth", SCRIPT)
truth = importlib.util.module_from_spec(spec)
spec.loader.exec_module(truth)


def write_sample(path, lines):
    path.write_bytes(gzip.compress(("\n".join([HEADER] + lines) + "\n").encode("utf-8")))
    return path


def test_caso_a_mano(tmp_path):
    sample = write_sample(tmp_path / "s.csv.gz", [
        "2024-03-01 10:00:00 UTC,view,1,2,a.b,acme,10.00,3,s1",
        "2024-03-01 10:00:00 UTC,view,1,2,a.b,acme,10.00,3,s1",  # copia 1
        "2024-03-01 10:00:00 UTC,view,1,2,a.b,acme,10.00,3,s1",  # copia 2
        "2024-03-01 23:59:59 UTC,cart,1,2,,,0.00,3,s1",  # category_code y brand nulos, price cero
        "2024-03-02 00:00:00 UTC,purchase,1,2,a.b,acme,-1.50,3,",  # otro día, sesión nula, negativo
        "2024-03-02 00:00:01 UTC,purchase,1,2,a.b,acme,,3,s2",  # price nulo: no es fuera de rango
    ])
    result = truth.compute(sample)
    d1, d2 = result["by_day"]["2024-03-01"], result["by_day"]["2024-03-02"]
    assert list(result["by_day"]) == ["2024-03-01", "2024-03-02"]
    assert d1["rows"] == 4 and d1["duplicate_copies"] == 2
    assert d1["rows_by_event_type"] == {"cart": 1, "purchase": 0, "view": 3}
    assert d1["nulls"]["category_code"] == 1 and d1["nulls"]["brand"] == 1 and d1["nulls"]["price"] == 0
    assert (d1["price_zero"], d1["price_negative"], d1["price_out_of_range"]) == (1, 0, 1)
    assert d2["nulls"]["user_session"] == 1 and d2["nulls"]["price"] == 1
    assert (d2["price_zero"], d2["price_negative"], d2["price_out_of_range"]) == (0, 1, 1)
    assert result["totals"]["rows"] == 6 and result["totals"]["distinct_rows"] == 4


def test_duplicado_en_dias_distintos_no_es_duplicado(tmp_path):
    sample = write_sample(tmp_path / "s.csv.gz", [
        "2024-03-01 10:00:00 UTC,view,1,2,a.b,acme,10.00,3,s1",
        "2024-03-02 10:00:00 UTC,view,1,2,a.b,acme,10.00,3,s1",
    ])
    assert truth.compute(sample)["totals"]["duplicate_copies"] == 0


def test_dos_ejecuciones_dan_el_mismo_fichero(tmp_path):
    outputs = [tmp_path / "a.json", tmp_path / "b.json"]
    for output in outputs:
        subprocess.run([sys.executable, str(SCRIPT), "--output", str(output)], check=True, capture_output=True)
    assert outputs[0].read_bytes() == outputs[1].read_bytes()


def test_el_fichero_subido_esta_al_dia():
    """Si falla: python evals/truth/compute_quality_truth.py"""
    assert QUALITY.read_text(encoding="utf-8") == truth.render(truth.compute(SAMPLE))  # tolera CRLF de git


def test_coincide_con_lo_documentado_en_la_muestra():
    """Cifras de infra/synthetic/README.md, copiadas a mano."""
    q = json.loads(QUALITY.read_text(encoding="utf-8"))
    t = q["totals"]
    assert t["rows"] == 50000 and t["distinct_rows"] == 49750 and t["duplicate_copies"] == 250
    assert t["nulls"] == {**{c: 0 for c in COLUMNS}, "category_code": 16853, "brand": 7261, "user_session": 50}
    assert (t["price_zero"], t["price_negative"]) == (100, 101)
    assert len(q["by_day"]) == 14
    assert list(q["by_day"])[0] == "2024-03-01" and list(q["by_day"])[-1] == "2024-03-14"
    purchases = {day: v["rows_by_event_type"]["purchase"] for day, v in q["by_day"].items()}
    assert purchases.pop("2024-03-10") == 50
    assert min(purchases.values()) == 53 and max(purchases.values()) == 86


def test_elemento_a_elemento_contra_recalculo_en_crudo():
    """Otra vía: líneas partidas a mano, Counter de líneas y fracciones, sin csv ni Decimal."""
    lines = gzip.decompress(SAMPLE.read_bytes()).decode("utf-8").splitlines()
    assert lines[0] == HEADER and '"' not in "".join(lines)
    raw = {}
    line_counts = Counter(lines[1:])
    for line in lines[1:]:
        f = line.split(",")
        assert len(f) == 9
        day = raw.setdefault(f[0][:10], {"rows": 0, "types": Counter(), "nulls": Counter(), "zero": 0, "neg": 0,
                                         "dups": 0})
        day["rows"] += 1
        day["types"][f[1]] += 1
        for column, value in zip(COLUMNS, f):
            day["nulls"][column] += value == ""
        if f[6] != "":
            day["zero"] += Fraction(f[6]) == 0
            day["neg"] += Fraction(f[6]) < 0
    for line, n in line_counts.items():
        raw[line[:10]]["dups"] += n - 1

    q = json.loads(QUALITY.read_text(encoding="utf-8"))
    assert sorted(q["by_day"]) == sorted(raw)
    for name, r in raw.items():
        d = q["by_day"][name]
        assert d["rows"] == r["rows"], name
        assert d["rows_by_event_type"] == {t: r["types"][t] for t in d["rows_by_event_type"]}, name
        assert set(d["rows_by_event_type"]) >= set(r["types"]), name
        assert d["nulls"] == {c: r["nulls"][c] for c in COLUMNS}, name
        assert d["duplicate_copies"] == r["dups"], name
        assert (d["price_zero"], d["price_negative"]) == (r["zero"], r["neg"]), name
        assert d["price_out_of_range"] == r["zero"] + r["neg"], name
    assert q["totals"]["distinct_rows"] == len(line_counts)
