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
| F3b | Structs, `impl` methods, enums | F3a | done | ff55202..6c609fa, pushed at 175e760 | `docs/evidence/f3b-structs/`; CI run 42338248852 green at 175e760 |
| F3c | Maps (insertion-ordered) | F3a | done | e8b52be..2018ca3, pushed at 552d5ba | `docs/evidence/f3c-maps/`; CI run 42340020516 green at 552d5ba |
| F4 | User modules; native `std/io`, `std/os`, `std/strings` | F3a–c | done | edafc1a..b0cce71, pushed at 242c7ac | `docs/evidence/f4-modules/`; CI run 42343930723 green at 242c7ac |
| F5 | `projects/ledger` application + independent oracles | F4 | done | 134bd0d..9f0ecf3, pushed at c317efb | `docs/evidence/f5-ledger/`; CI run 42346474485 green at c317efb; fresh clone gates green (`docs/evidence/milestone/`) |
| — | float, sum types + match, lambdas, sets, GC | after milestone | planned | | |

## Runtime readiness (goal: `docs/RUNTIME_READINESS_GOAL.md`, renewed 2026-11-25)

| # | Feature | Depends on | Status | Commit | Evidence |
|---|---|---|---|---|---|
| R1 | Memory reclamation: non-moving mark-sweep collector (spec `docs/superpowers/specs/2026-11-25-memory-reclamation-design.md`) | F5 | done | a984cbc..e231e8e, pushed at f61099c; CI run 45564871470 green; fresh clone green | `docs/evidence/r1-gc/` (RED suite, collector, ledger ×1/×3/×10 against the oracle) |
| R2 | Typing gaps met by applications: cross-module array element types, struct values via map-field locals | R1 | in progress | | |
| R3 | Scalability: cached UTF-16 offsets for non-ASCII indexing; a native sort for distinct keys if a lambda-free API fits the reference | R1 | planned | | |

Historical baseline (supervisor acceptance, 2026-11-25, automation evidence `acceptance-20261125/`; not re-measured here):
the ledger at 313 141 / 939 355 / 3 131 104 input bytes peaked at 28 404 / 81 620 / 268 416 KiB RSS (never-free).
Final R1 runtime (measured at 6a889ec, `docs/evidence/r1-gc/ledger/final-6a889ec/`): the same inputs peak at
7 580 / 9 440 / 20 376 KiB RSS (heap 4 927 / 6 634 / 16 188 KiB, 6 / 19 / 34 collections, 0.02 / 0.07 / 0.26 s), byte-identical to the Python oracle.

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

## Milestone status and remaining limitations

The milestone application is `projects/ledger` (F5). This section lists what the toolchain still does not do
after it; F5's status is in the table above.

- **Memory: collector with remaining limits.** The runtime has a conservative, non-moving mark-sweep
  collector (R1), so unreachable strings, arrays, structs and maps are freed, cycles included. On the ledger
  (`projects/ledger/README.md`, "Resource use"; `docs/evidence/r1-gc/ledger/`), 313 KB / 939 KB / 3.1 MB of
  input peak at 7.3 / 9.4 / 19.9 MB RSS with output identical to the Python oracle. The never-free runtime
  peaked at 28 / 82 / 268 MB. Remaining limits:
  - Live data stays in memory. An input read whole by `readLines` (or `readFile`) stays live while it is
    referenced, so the ledger's peak still grows with its input, at about 4.6 bytes per input byte.
  - Conservative scanning can retain some garbage that a word merely appears to reference.
  - Collections are stop-the-world pauses.
  - The collector is single-threaded, like the programs it serves.
- **Not in the subset:** `float`; sum types and `match`; lambdas and closures (so no `sort`, `map`, `filter`,
  `reduce`, `forEach`; the ledger hand-writes an insertion sort); sets; interfaces, actors, generic functions,
  `scope`/`spawn`/`send`; `import java` and every other Java interop; most of the std library beyond `std/io`,
  `std/os` and `std/strings` (docs/SUBSET.md, "Rejected").
- **Typing gaps met by the application** (accepted by the reference, rejected here or untyped): array elements
  returned from another module and struct values read through a map local inferred from a field are `any`;
  each needs a typed local or a struct-typed parameter (docs/SUBSET.md, "Accepted").
- **Error message deviations:** some `NullPointerException` messages lack the JVM's helpful `because …` text (see
  docs/SUBSET.md, Deviations); uncaught exceptions print no stack trace. The Deviations table lists further
  deviations (e.g. `toUpper`/`toLower` Unicode tables).
- **Stack overflow cannot be caught:** it ends the program as an uncaught error, on a fixed 16 MiB program
  stack.
- **Quadratic non-ASCII index scans:** on strings with non-ASCII characters, `substring`, `indexOf` and
  other index operations scan from the start of the string, so a per-character loop over long non-ASCII text
  is quadratic (see the F1 follow-up above; not needed by the ledger's input sizes).
- **Platform:** Linux with glibc, tested on x86_64 with clang 19 (CI ubuntu-24.04 and local 19.1.7); aarch64 and clang 15–18 are intended but untested (the code requires clang ≥ 15 for opaque pointers); no Windows or macOS.
- **Single-threaded:** programs run on one program thread; no concurrency primitives.

## Known limitations (current)

See `docs/SUBSET.md`. Updated as features land.

## Incidents

- 2026-11-07: two controller sessions were OOM-killed (4 GiB scope) by the
  native `concat_loop` test running against the pre-buffer runtime, which
  copied on every concatenation (~3.6 GB RSS). Fixed by appendable string
  buffers (8ece096; peak RSS 7.8 MB). Since then all Maven, oracle and native
  runs go through the automation's 768 MiB bounded wrapper
  (the automation's BOUNDED.md).

## Continuity notes

- Status (2026-11-25, run 7): R1 published at f61099c. R2 Tasks 1–2 reviewed locally (3d785fd..a83579d), not
  pushed. Next: R2 Task 3 (ledger workaround removal with unchanged oracle outputs), whole-feature review,
  final gates, fresh clone, publish. Ledger: `.superpowers/sdd/2026-11-25-r2-typing-gaps/progress.md`.
- Local clang 19 is provided in `$SIYOLLVM_CLANG`. `mvn` on this host is a memory-bounded wrapper; run other
  probes through `python3 ~/.hermes/automation/siyollvm/bounded.py -- CMD`.
- SDD ledgers live in `.superpowers/sdd/<plan>/progress.md` (git-ignored).
- Reference oracle: `scripts/reference-oracle.sh build` then `run <case.siyo>`.
