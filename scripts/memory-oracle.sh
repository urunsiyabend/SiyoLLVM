#!/bin/sh
# Generates the stdout expectations of a memory-bound workload
# (src/test/resources/memory/<name>/) with the reference JVM compiler, via
# scripts/reference-oracle.sh (see there for the reference jar):
#
#   scripts/memory-oracle.sh <workload-dir> [N...]
#
# For each N (default: the numbers in <workload-dir>/sizes) it runs
# `reference-oracle.sh run <workload-dir>/main.siyo N` and renames the
# resulting main.expected to expected-<N>.stdout. A workload must exit 0 with
# empty stderr on the reference; anything else is an error and leaves no
# expectation behind. Run it through the bounded helper, e.g.
#   python3 ~/.hermes/automation/siyollvm/bounded.py -- sh scripts/memory-oracle.sh src/test/resources/memory/gc_maps
set -eu

here=$(dirname "$0")
dir=${1:?usage: $0 <workload-dir> [N...]}
shift
if [ $# -eq 0 ]; then set -- $(cat "$dir/sizes"); fi
for n in "$@"; do
    sh "$here/reference-oracle.sh" run "$dir/main.siyo" "$n"
    if [ -e "$dir/main.expected-stderr" ] || [ -e "$dir/main.expected-exit" ]; then
        echo "$dir N=$n: the reference did not exit 0 with empty stderr:" >&2
        cat "$dir/main.expected-stderr" "$dir/main.expected-exit" >&2 2>/dev/null || true
        rm -f "$dir/main.expected" "$dir/main.expected-stderr" "$dir/main.expected-exit"
        exit 1
    fi
    mv "$dir/main.expected" "$dir/expected-$n.stdout"
done
