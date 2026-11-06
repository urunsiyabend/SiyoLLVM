# SiyoLLVM roadmap — useful native toolchain milestone

Goal: `docs/DEVELOPMENT_GOAL.md`. Design: `docs/superpowers/specs/2026-11-07-native-toolchain-design.md`.
Branch: `development/native-toolchain` (never merged to main under this mandate).
Evidence: `docs/evidence/<feature>/`.

Status legend: `done` (tested, reviewed, pushed, CI green at that head),
`in progress`, `planned`, `blocked`.

| # | Feature | Depends on | Status | Commit | Evidence |
|---|---|---|---|---|---|
| F0 | Spec, roadmap, oracle script, PR for CI | — | in progress | 62218e8 | — |
| F1 | C runtime linked by clang; tagged heap values; strings | F0 | planned | | |
| F2 | Exceptions: try/catch/throw, catchable runtime errors | F1 | planned | | |
| F3 | Arrays, for-in, structs + impl, enums, maps | F1, F2 | planned | | |
| F4 | User modules; native `std/io`, `std/os`, `std/strings` | F3 | planned | | |
| F5 | `projects/ledger` application + independent oracles | F4 | planned | | |
| — | float, sum types + match, lambdas, sets, GC | after milestone | planned | | |

## Acceptance criteria

**F1** — `+` on strings with int/long/bool/string operands, `+=`,
interpolation, `toString`, `len`, `substring`, `indexOf`, `contains`,
`startsWith`, `endsWith`, `replace`, `trim`, `toUpper`, `toLower`, `split`,
`chr`, `ord`, `parseInt`, `parseLong`, `toLong`, `<`/`<=`/`>`/`>=` on strings
compile and match the JVM reference byte for byte (UTF-16 indexing, Unicode,
embedded NUL, `LC_ALL=C`), with oracle-generated corpus files. All v0.1 corpus
programs still pass. Out-of-range `substring` is a runtime error with the JVM
first line and exit 1 (catchable once F2 lands).

**F2** — `try`/`catch` (statement), `throw` of any accepted value, `error()`;
division by zero, string index errors and later bounds errors are catchable
with the JVM message; uncaught errors print the JVM first line and exit 1
after flushing stdout. Throw across function calls and out of nested loops.

**F3** — arrays of every accepted type (including structs and nested
arrays), literal/index/index-assign with bounds errors, `push`, `pop`,
`removeAt`, `len`, `for x in arr`, `for i in range(a, b)`; structs with
literals, field read/write, `impl` methods, `toString`; enums; maps with
string and int keys in insertion order, index read/write, `has`, `keys`,
`for k in m`, `len`, `toString`. Aliasing (two names, one array), struct
field mutation through a parameter, and initialisation-order tests.

**F4** — `import "x"` / `import "dir/x"` / `as` aliases resolved like
`siyoc`; cross-module functions, globals, structs, enums and init order;
`std/io` file functions on real files (Unicode paths, missing file error
messages), `std/os.args()`/`exit()`, `std/strings` helpers used by the
application; `build` of a multi-file program from any working directory.

**F5** — `projects/ledger` builds with `bin/siyollvm build` from a fresh clone
(no SiyoCompiler checkout, no JVM in the executable), passes its fixtures
against both oracles, and is part of the native gate.

## Known limitations (current)

See `docs/SUBSET.md`. Updated as features land.

## Continuity notes

- Local clang 19: `SIYOLLVM_CLANG=$HOME/.hermes/cache/scratch/siyo-llvm-toolchain/bin/clang`.
- Reference oracle: `scripts/reference-oracle.sh build` then `run <case.siyo>`.
