import sys, time, re
path, secs = sys.argv[1], int(sys.argv[2])
end = time.time() + secs
while time.time() < end:
    text = open(path, encoding="utf-8", errors="replace").read()
    m = re.findall(r"(Tests run:[^\n]*\n|BUILD [A-Z]+|\[ERROR\][^\n]*(?:FAIL|Tests)[^\n]*)", text[-200000:])
    if any("BUILD" in x or "Tests run" in x for x in m) and "Tests run:" in text[-3000:]:
        break
    time.sleep(10)
print("\n".join(m[-15:]))
