import glob, json, os, sys, statistics as st
sys.stdout.reconfigure(encoding="utf-8")
CUT = "20261107-1450"
rows = []
for f in glob.glob(os.path.join(os.path.dirname(__file__), "..", "..", "board", "runs", "live-*-tester-*.jsonl")):
    stamp = os.path.basename(f)[:-6].split("-tester-")[1]
    cost = None
    model = set()
    for line in open(f, encoding="utf-8"):
        try:
            o = json.loads(line)
        except Exception:
            continue
        if "total_cost_usd" in o:
            cost = o["total_cost_usd"]
        m = o.get("message", {}).get("model") if isinstance(o.get("message"), dict) else None
        if m:
            model.add(m)
    rows.append((stamp, os.path.basename(f), cost, ",".join(sorted(model))))
rows.sort()
for grp, sel in (("antes", [r for r in rows if r[0] < CUT]), ("despues", [r for r in rows if r[0] >= CUT])):
    c = [r[2] for r in sel if r[2] is not None]
    print(grp, len(sel), "con coste", len(c), "media", round(st.mean(c), 3) if c else None,
          "mediana", round(st.median(c), 3) if c else None)
for r in rows[-8:]:
    print(r)
