#!/bin/sh
# Checks that the native gate fails, and fails *because clang is missing*:
# runs the native corpus with SIYOLLVM_CLANG pointing nowhere and requires
# both a Maven failure and SiyoLLVM's missing-clang diagnostic in the output.
# Any other failure (compile error, broken test) makes this check fail too.
#
#   scripts/check-native-gate-requires-clang.sh [extra maven args...]
#
# MVN selects the Maven command (default: mvn).
set -u
root=$(cd "$(dirname "$0")/.." && pwd)
log=$(mktemp "${TMPDIR:-/tmp}/siyollvm-gate-check.XXXXXX")
trap 'rm -f "$log"' EXIT

missing=/nonexistent/siyollvm-test/clang
expected="SIYOLLVM_CLANG=$missing does not name an executable clang"

(cd "$root" && SIYOLLVM_CLANG="$missing" ${MVN:-mvn} -B test -Pnative -Dtest=NativeCorpusTest "$@") > "$log" 2>&1
status=$?

if [ "$status" -eq 0 ]; then
    tail -n 40 "$log"
    echo "FAIL: the native gate passed without a usable clang" >&2
    exit 1
fi
if ! grep -qF "$expected" "$log" || ! grep -q 'NativeCorpusTest' "$log"; then
    tail -n 60 "$log"
    echo "FAIL: Maven failed (exit $status), but not with the missing-clang diagnostic:" >&2
    echo "      expected: $expected" >&2
    exit 1
fi
echo "OK: native gate fails without clang, reporting: $expected"
