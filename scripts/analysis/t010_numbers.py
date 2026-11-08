import csv, gzip, sys
from collections import Counter, defaultdict

sys.stdout.reconfigure(encoding="utf-8")
rows = list(csv.DictReader(gzip.open("testdata/synthetic-events.csv.gz", "rt", encoding="utf-8", newline="")))
nim = lambda r: r["brand"] == "synth-nimbora"
for name, t, k in [("purchase nim", "purchase", nim), ("purchase otras", "purchase", lambda r: not nim(r)),
                   ("purchase total", "purchase", lambda r: True), ("view nim", "view", nim), ("cart nim", "cart", nim)]:
    c = Counter(r["event_time"][:10] for r in rows if r["event_type"] == t and k(r))
    o = [n for d, n in c.items() if d != "2024-03-10"]
    print(name, c["2024-03-10"], min(o), max(o), len(c))
s = defaultdict(list)
for r in rows:
    if r["user_session"]:
        s[r["user_session"]].append(r["event_time"][:10])
print("sessions", len(s), "cross", sum(1 for v in s.values() if min(v) != max(v)))
print("sess distinct", len({(r["user_session"]) for r in rows if r["user_session"]}))
