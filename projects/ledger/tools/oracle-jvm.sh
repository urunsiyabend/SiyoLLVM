#!/bin/sh
# Generates the expected outputs of projects/ledger's cases with the JVM
# reference (`siyoc run`, SiyoCompiler 0.9.0 at 02c2262; build it with
# scripts/reference-oracle.sh build).
#
#   projects/ledger/tools/oracle-jvm.sh [case-name...]    (default: every case)
#
# For each case directory tests/<case>/ (args: one argument per line;
# files/: the inputs), the program's sources (src/) are copied to a scratch
# directory and run from a second, fresh scratch directory holding a copy of
# files/, under LC_ALL=C, with stdin from /dev/null. Only these files are
# written back into the case directory:
#   expected.stdout  stdout bytes
#   expected.stderr  stderr (the reference adds a Java stack trace only for an
#                    uncaught exception, which the application must not have;
#                    more than one line of stderr is an error here)
#   expected.exit    exit status
#   expected.report  the report file (the second argument) after the run,
#                    when it exists; removed otherwise
# Review every generated file before committing it.
#
# Environment: ORACLE_HOME (default ~/.cache/siyollvm-oracle), TMPDIR.
set -eu

ORACLE_HOME=${ORACLE_HOME:-$HOME/.cache/siyollvm-oracle}
JAR=$ORACLE_HOME/siyoc-02c2262/target/siyo-compiler-0.9.0.jar
[ -f "$JAR" ] || { echo "reference jar missing; run: scripts/reference-oracle.sh build" >&2; exit 2; }

project=$(cd "$(dirname "$0")/.." && pwd)
if [ $# -eq 0 ]; then
    set --
    for d in "$project"/tests/*/; do set -- "$@" "$(basename "$d")"; done
fi

scratch=$(mktemp -d "${TMPDIR:-/tmp}/ledger-oracle.XXXXXX")
trap 'rm -rf "$scratch"' EXIT
mkdir "$scratch/program"
cp -R "$project/src"/. "$scratch/program"/

for name in "$@"; do
    case_dir=$project/tests/$name
    [ -f "$case_dir/args" ] || { echo "$name: missing $case_dir/args" >&2; exit 2; }
    run=$scratch/run-$name
    mkdir "$run"
    if [ -d "$case_dir/files" ]; then cp -R "$case_dir/files"/. "$run"/; fi
    # The arguments: one per line of args.
    set --
    while IFS= read -r line || [ -n "$line" ]; do set -- "$@" "$line"; done < "$case_dir/args"
    report=
    if [ $# -ge 2 ]; then report=$2; fi
    set +e
    (cd "$run" && env -u LANG LC_ALL=C java -jar "$JAR" run "$scratch/program/main.siyo" "$@") \
        > "$scratch/stdout" 2> "$scratch/stderr" < /dev/null
    status=$?
    set -e
    if [ "$(wc -l < "$scratch/stderr")" -gt 1 ]; then
        echo "$name: more than one line of stderr (uncaught exception?):" >&2
        cat "$scratch/stderr" >&2
        exit 1
    fi
    if [ "$status" -eq 2 ] && [ -s "$scratch/stdout" ]; then
        echo "$name: exit 2 with output on stdout" >&2
        exit 1
    fi
    cp "$scratch/stdout" "$case_dir/expected.stdout"
    cp "$scratch/stderr" "$case_dir/expected.stderr"
    echo "$status" > "$case_dir/expected.exit"
    if [ -n "$report" ] && [ -f "$run/$report" ]; then
        cp "$run/$report" "$case_dir/expected.report"
    else
        rm -f "$case_dir/expected.report"
    fi
    echo "$name: exit $status"
done
