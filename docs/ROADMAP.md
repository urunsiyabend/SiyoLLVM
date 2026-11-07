# SiyoLLVM roadmap — useful native toolchain milestone

Goal: `docs/DEVELOPMENT_GOAL.md`. Design: `docs/superpowers/specs/2026-11-07-native-toolchain-design.md`.
Branch: `development/native-toolchain` (never merged to main under this mandate).
Evidence: `docs/evidence/<feature>/`.

Status legend: `done` (tested, reviewed, pushed, CI green at that head),
`in progress`, `planned`, `blocked`.

| # | Feature | Depends on | Status | Commit | Evidence |
|---|---|---|---|---|---|
| F0 | Spec, roadmap, oracle script, PR for CI | — | done | 62218e8, 0d5903f | PR #1; CI run 42307548296 green at 0d5903f |
| F1 | C runtime linked by clang; appendable WTF-8 strings; string builtins | F0 | done | 90f3129..8d9af60, pushed at 2774fc7 | `docs/evidence/f1-strings/`; CI run 42328908151 green at 2774fc7 |
| F2 | `any` values; exceptions: try/catch/throw, try expressions, catchable runtime errors | F1 | done | de865b5..0c1c449, pushed at 4f934b2 | `docs/evidence/f2-exceptions/`; CI run 42333036738 green at 4f934b2 |
| F3a | Arrays, `for … in`, `range`, `split`, `s[i]` | F2 | done | 1d0e5ae..2e5c8dd, pushed at 1abd7b0 | `docs/evidence/f3a-arrays/`; CI run 42336285628 green at 1abd7b0 |
| F3b | Structs, `impl` methods, enums | F3a | in progress (gates green at 6c609fa; publishing) | ff55202..6c609fa | `docs/evidence/f3b-structs/` |
| F3c | Maps (insertion-ordered) | F3a | planned | | |
| F4 | User modules; native `std/io`, `std/os`, `std/strings` | F3a–c | planned | | |
| F5 | `projects/ledger` application + independent oracles | F4 | planned | | |
| — | float, sum types + match, lambdas, sets, GC | after milestone | planned | | |

## Acceptance criteria

**F1** — `+` on strings with int/long/bool/string operands, `+=`,
interpolation, `toString`, `len`, `substring`, `indexOf`, `contains`,
`startsWith`, `endsWith`, `replace`, `trim`, `toUpper`, `toLower`,
`chr`, `ord`, `parseInt`, `parseLong`, `toLong`, `<`/`<=`/`>`/`>=` on strings
compile and match the JVM reference byte for byte (UTF-16 indexing, Unicode,
embedded NUL, `LC_ALL=C`), with oracle-generated corpus files. All v0.1 corpus
programs still pass. Out-of-range `substring` is a runtime error with the JVM
first line and exit 1 (catchable once F2 lands).

**F2** — `try`/`catch` (statement), `throw` of any accepted value, `error()`;
division by zero, string index errors and later bounds errors are catchable
with the JVM message; uncaught errors print the JVM first line and exit 1
after flushing stdout. Throw across function calls and out of nested loops.

**F3a–c** — `split` (returns `string[]`); arrays of every accepted type (including structs and nested
arrays), literal/index/index-assign with bounds errors, `push`, `pop`,
`removeAt`, `len`, `for x in arr`, `for i in range(a, b)`; structs with
literals, field read/write, `impl` methods, `toString`; enums; maps with
string and int keys in insertion order, index read/write, `has`, `keys`,
`for k in m`, `len`, `toString`. Aliasing (two names, one array), struct
field mutation through a parameter, and initialisation-order tests.

*Planned follow-up (from F1):* on non-ASCII strings, `substring`, `indexOf`
and other index operations scan the WTF-8 bytes from the start of the string
on every call, so a per-character loop over long non-ASCII input is quadratic.
To be addressed (for example with a cached UTF-16 offset index) if the F5
application needs it.

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

## Incidents

- 2026-11-07: two controller sessions were OOM-killed (4 GiB scope) by the
  native `concat_loop` test running against the pre-buffer runtime, which
  copied on every concatenation (~3.6 GB RSS). Fixed by appendable string
  buffers (8ece096; peak RSS 7.8 MB). Since then all Maven, oracle and native
  runs go through the automation's 768 MiB bounded wrapper
  (`~/.hermes/automation/siyollvm/BOUNDED.md`).

## Continuity notes

- Local clang 19 is provided in `$SIYOLLVM_CLANG`. `mvn` on this host is a memory-bounded wrapper; run other
  probes through `python3 ~/.hermes/automation/siyollvm/bounded.py -- CMD`.
- SDD ledgers live in `.superpowers/sdd/<plan>/progress.md` (git-ignored).
- Reference oracle: `scripts/reference-oracle.sh build` then `run <case.siyo>`.
