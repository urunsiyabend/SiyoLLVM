#!/usr/bin/env python3
"""Compares a native run directory with an oracle run directory byte for byte: exit code,
stdout, stderr (the native side minus its final 'siyo-rt: ' stats line) and the report.
Prints one JSON line; exits 1 on any difference."""
import json, sys
from pathlib import Path
nat, ora = Path(sys.argv[1]), Path(sys.argv[2])
def rd(d, n): return (d / n).read_bytes()
nerr = rd(nat, 'stderr').splitlines(keepends=True)
stats = [l for l in nerr if l.startswith(b'siyo-rt: ')]
assert len(stats) == 1 and nerr[-1] == stats[0], 'expected exactly one trailing stats line'
res = {
    'exit': [int(rd(nat, 'exit')), int(rd(ora, 'exit'))],
    'stdout_equal': rd(nat, 'stdout') == rd(ora, 'stdout'),
    'stderr_equal': b''.join(nerr[:-1]) == rd(ora, 'stderr'),
    'report_equal': rd(nat, 'out.txt') == rd(ora, 'out.txt'),
    'stats': stats[0].decode().strip(),
}
res['match'] = res['exit'][0] == res['exit'][1] and res['stdout_equal'] and res['stderr_equal'] and res['report_equal']
print(json.dumps(res))
sys.exit(0 if res['match'] else 1)
