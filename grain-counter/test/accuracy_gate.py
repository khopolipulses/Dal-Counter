#!/usr/bin/env python3
"""Fails (exit 1) when the benchmark result is worse than the agreed minimum.

Usage: accuracy_gate.py <Eval output> <min exact> <min within +-2>
"""
import re
import sys

text = open(sys.argv[1]).read()
m = re.search(r"exact: (\d+)/(\d+)\s+within \+-2: (\d+)/(\d+)", text)
if not m:
    sys.exit("accuracy gate: no summary line in " + sys.argv[1])
exact, total, close = int(m.group(1)), int(m.group(2)), int(m.group(3))
need_exact, need_close = int(sys.argv[2]), int(sys.argv[3])
print("accuracy gate: exact %d/%d (need %d), within +-2 %d/%d (need %d)" % (exact, total, need_exact, close, total, need_close))
if exact < need_exact or close < need_close:
    sys.exit("accuracy gate: FAILED - the counting engine got less accurate")
