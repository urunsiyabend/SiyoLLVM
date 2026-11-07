#!/bin/sh
# Generates expectation files for a corpus program with the reference JVM
# compiler: siyoc 0.9.0 built from SiyoCompiler 02c226298296fb4379f7251a9dfba19b7e887f3d.
#
#   scripts/reference-oracle.sh build             # builds the reference jar into $ORACLE_HOME
#   scripts/reference-oracle.sh run <case.siyo> [args...]
#
# `run` writes <case>.expected (stdout), <case>.expected-stderr (first line of
# stderr, as SiyoLLVM prints no Java stack trace; omitted when empty) and
# <case>.expected-exit (omitted when 0), running under LC_ALL=C (see below).
# Review every generated file before committing it.
# A multi-file case is a directory: `run <dir>/main.siyo` writes
# <dir>/main.expected(-stderr|-exit).
#
# The program never runs in the source tree. Its directory is copied to a
# scratch directory (under $TMPDIR), where its imports resolve, and it runs
# from a second, fresh scratch directory holding a copy of <dir>/files/ (the
# case's fixtures; may be absent), so files it writes land there. Its
# arguments are those given after the source, or else the lines of
# <case>.args (one argument per line, UTF-8; an empty line is an empty
# argument). Only the expectation files are written back.
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
    if [ $# -eq 0 ] && [ -f "$base.args" ]; then
        while IFS= read -r line || [ -n "$line" ]; do set -- "$@" "$line"; done < "$base.args"
    fi
    scratch=$(mktemp -d)
    trap 'rm -rf "$scratch"' EXIT
    mkdir "$scratch/program" "$scratch/run"
    cp -R "$dir"/. "$scratch/program"/
    if [ -d "$dir/files" ]; then cp -R "$dir/files"/. "$scratch/run"/; fi
    set +e
    (cd "$scratch/run" && env -u LANG LC_ALL=C java -jar "$JAR" run "$scratch/program/$(basename "$source")" "$@") \
        > "$scratch/stdout" 2> "$scratch/stderr" < /dev/null
    status=$?
    set -e
    cp "$scratch/stdout" "$base.expected"
    if [ -s "$scratch/stderr" ]; then head -n 1 "$scratch/stderr" > "$base.expected-stderr"; else rm -f "$base.expected-stderr"; fi
    if [ "$status" -ne 0 ]; then echo "$status" > "$base.expected-exit"; else rm -f "$base.expected-exit"; fi
    echo "$base: exit $status"
    ;;
*)
    echo "usage: $0 build | run <case.siyo> [args...]" >&2
    exit 2
    ;;
esac
