import sys
sys.path.insert(0, "board")
import runner
pm = runner.pm
n = sum(len(x) + 3 for x in [*runner.ALLOWED, *pm.rules()[0], *runner.DENIED, *pm.rules()[1]])
print(n, sum(len(x) + 3 for x in runner.DENIED), len(pm.never_rules()), len(pm.rules()[1]))
print(sum(len(x) + 3 for x in runner.ALLOWED), sum(len(x) + 3 for x in pm.rules()[0]))
