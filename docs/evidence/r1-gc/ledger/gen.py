#!/usr/bin/env python3
"""Writes input.csv for scale factor N exactly as the 2026-11-25 supervisor acceptance
(~/.hermes/automation/siyollvm/acceptance-20261125/probe.py) built it: the header line of
tests/large/files/large.csv followed by its data lines repeated N times."""
import sys
src, factor, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
data = open(src, 'rb').read().splitlines(keepends=True)
raw = data[0] + b''.join(data[1:]) * factor
open(out, 'wb').write(raw)
print(len(raw))
