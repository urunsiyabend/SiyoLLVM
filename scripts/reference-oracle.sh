#!/bin/sh
# Generates expectation files for a corpus program with the reference JVM
# compiler: siyoc 0.9.0 built from SiyoCompiler 02c226298296fb4379f7251a9dfba19b7e887f3d.
#
#   scripts/reference-oracle.sh build             # builds the reference jar into $ORACLE_HOME
#   scripts/reference-oracle.sh run <case.siyo> [args...]
#
# `run` writes <case>.expected (stdout), <case>.expected-stderr (first line of
# stderr, as SiyoLLVM prints no Java stack trace; omitted when empty) and
# <case>.expected-exit (omitted when 0), running under LC_ALL=C from the
# program's directory. Review every generated file before committing it.
#
# Environment: SIYOC_CHECKOUT (default ../SiyoCompiler; only read with
# `git archive`), ORACLE_HOME (default ~/.cache/siyollvm-oracle).
set -eu

COMMIT=02c226298296fb4379f7251a9dfba19b7e887f3d
ORACLE_HOME=${ORACLE_HOME:-$HOME/.cache/siyollvm-oracle}
TREE=$ORACLE_HOME/siyoc-02c2262
JAR=$TREE/target/siyo-compiler-0.9.0.jar

case "${1:-}" in
build)
    checkout=${SIYOC_CHECKOUT:-$(dirname "$0")/../../SiyoCompiler}
    rm -rf "$TREE"
    mkdir -p "$TREE"
    git -C "$checkout" archive "$COMMIT" | tar -x -C "$TREE"
    (cd "$TREE" && mvn -B -q package -DskipTests)
    echo "$JAR"
    ;;
run)
    shift
    [ -f "$JAR" ] || { echo "reference jar missing; run: $0 build" >&2; exit 2; }
    source=$1
    shift
    base=${source%.siyo}
    dir=$(dirname "$source")
    err=$(mktemp)
    set +e
    (cd "$dir" && env -u LANG LC_ALL=C java -jar "$JAR" run "$(basename "$source")" "$@") \
        > "$base.expected" 2> "$err" < /dev/null
    status=$?
    set -e
    if [ -s "$err" ]; then head -n 1 "$err" > "$base.expected-stderr"; else rm -f "$base.expected-stderr"; fi
    if [ "$status" -ne 0 ]; then echo "$status" > "$base.expected-exit"; else rm -f "$base.expected-exit"; fi
    rm -f "$err"
    echo "$base: exit $status"
    ;;
*)
    echo "usage: $0 build | run <case.siyo> [args...]" >&2
    exit 2
    ;;
esac
