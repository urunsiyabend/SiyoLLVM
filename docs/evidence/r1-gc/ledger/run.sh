#!/bin/sh
# One bounded run: run.sh DIR LABEL CMD...  (cwd DIR; stdout, stderr, exit, time -v output and
# the bounded receipt go to DIR/LABEL.*). Native runs get SIYO_RT_STATS=1; all runs LC_ALL=C.
set -u
dir=$1; label=$2; shift 2
B=/home/urunsiyabend/.hermes/automation/siyollvm/bounded.py
cd "$dir" || exit 2
# bounded.py exits non-zero whenever the child does (it rejects the run), so the child's own exit
# code is taken from the receipt; the ledger exits 3 when rows are rejected.
python3 "$B" -- sh -c 'exec env LC_ALL=C SIYO_RT_STATS=1 /usr/bin/time -v -o time.txt "$@" >stdout 2>stderr' run "$@" 2>bounded.txt
ev=$(sed -n 's/^BOUNDED accepted=.* evidence=//p' bounded.txt)
cp "$ev/receipt.json" receipt.json
python3 -I -c 'import json,sys; r=json.load(open("receipt.json")); print(r["returncode"]); sys.exit(r["oom_kill_delta"] != 0)' > exit
