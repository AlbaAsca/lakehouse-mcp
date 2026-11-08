"""make profile-data: el informe da los recuentos correctos y no filtra identificadores."""
import re
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[1]
SQL = ROOT / "infra" / "profile-data.sql"
HEADER = "event_time,event_type,product_id,category_id,category_code,brand,price,user_id,user_session\n"
ROWS = [
    "2019-10-01 00:00:01 UTC,view,1,10,a.b,acme,10.5,USERAAA111,SESSIONAAA111\n",
    "2019-10-01 00:00:05 UTC,cart,1,10,a.b,acme,10.5,USERAAA111,SESSIONAAA111\n",
    "2019-10-01 00:00:05 UTC,cart,1,10,a.b,acme,10.5,USERAAA111,SESSIONAAA111\n",  # duplicate
    "2019-10-02 00:00:00 UTC,view,2,11,,,20,USERBBB222,SESSIONBBB222\n",           # null category_code, brand
    "2019-10-01 23:59:59 UTC,purchase,3,12,c.d,zed,5,USERCCC333,SESSIONCCC333\n",  # out of order
]


def test_script_is_aggregates_only():
    text = SQL.read_text(encoding="utf-8")
    assert "@CSV@" in text and "@OUT@" in text
    sql = "\n".join(l for l in text.splitlines() if not l.startswith("--"))
    # nothing selects user_id / user_session as a plain column: only counted (or used as an output alias)
    for m in re.finditer(r"user_(id|session)", sql):
        before = sql[max(0, m.start() - 20):m.start()]
        assert "count(DISTINCT " in before or "NULLIF(" in before or before.endswith("AS ")


@pytest.mark.skipif(shutil.which("duckdb") is None, reason="DuckDB CLI only lives in the tools image")
def test_profile_counts_and_no_identifiers(tmp_path):
    csv = tmp_path / "events.csv"
    csv.write_text(HEADER + "".join(ROWS), encoding="utf-8")
    out = tmp_path / "profile.md"
    sql = SQL.read_text(encoding="utf-8").replace("@CSV@", csv.as_posix()).replace("@OUT@", out.as_posix())
    subprocess.run(["duckdb"], input=sql, text=True, check=True, capture_output=True)
    report = out.read_text(encoding="utf-8")
    assert "USER" not in report and "SESSION" not in report
    cells = lambda title: [c.strip() for c in
                           report.split(title)[1].split("##")[0].strip().splitlines()[2].strip("|").split("|")]
    assert cells("## Volume")[:2] == ["5", "2"]
    assert cells("## Volume")[4:6] == ["3", "3"]
    nulls = cells("## Nulls")
    assert nulls[4:6] == ["1", "1"] and nulls[7:] == ["0", "0"]
    assert cells("## Duplicates")[0] == "1"
    assert cells("## Order")[0] == "1"
