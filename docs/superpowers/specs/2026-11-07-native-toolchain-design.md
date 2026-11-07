# SiyoLLVM native toolchain — design

Date: 2026-11-07
Status: approved under the owner's standing mandate in
`docs/DEVELOPMENT_GOAL.md` (non-interactive; the mandate is the approval gate
for routine technical design). Owner-dependent decisions are listed at the end.

## Intent

Stated by the owner (`docs/DEVELOPMENT_GOAL.md`):

- Turn the scalar-only compiler into a native toolchain that builds and runs a
  nontrivial **multi-module** command-line application doing **real data
  processing and file I/O**, from a fresh clone, with no SiyoCompiler checkout
  and no JVM in generated programs.
- Grow coverage by coherent vertical slices, each tested end to end, reviewed,
  committed and pushed to `development/native-toolchain`; no main merge, tag
  or release.
- No silent fallback, semantic substitution or unsupported claims; measured
  deviations are documented. Existing programs keep working.

Assumptions taken without a round-trip:

- Reference semantics remain `siyoc run` built from the pinned SiyoCompiler
  `02c226298296fb4379f7251a9dfba19b7e887f3d` (0.9.0).
  `scripts/reference-oracle.sh` rebuilds it from a SiyoCompiler checkout with
  `git archive` (the checkout is never modified) and generates expectation
  files. The oracle is a development tool only; no build or test needs it.
- clang stays the only native dependency (≥ 15, CI uses 19).

## Application target

`projects/ledger`: a bank-statement reconciler CLI.

```
ledger <transactions.csv> <report-out.txt>
```

It reads a CSV of transactions (`date,account,category,amount_cents,memo`),
validates each row (field count, date shape, integer amount, known account
name), aggregates per account and per category with maps, keeps the rejected
rows with line numbers and reasons, writes a fixed-format report file and
prints a summary on stdout. Exit status: 0 clean, 3 when some rows were
rejected, 2 for usage errors or an unreadable input (message on stderr).

Modules: `main.siyo` (args, orchestration, exit status), `csv.siyo` (line
splitting and quoted fields), `model.siyo` (`Transaction`, `Rejection`
structs, validation), `ledger.siyo` (aggregation), `report.siyo` (formatting,
padding, sorting). Fixtures cover clean input, malformed rows, quoting,
Unicode memos, an empty file and a missing file. Oracles: (1) the JVM
reference `siyoc run` on the same sources and fixtures, (2) an independent
Python implementation (`projects/ledger/oracle/ledger.py`) written from the
format description, not from the Siyo code.

What the application needs, in dependency order, defines the features below.

## Architecture changes

### 1. C runtime linked by clang

The runtime grows from print helpers to strings, collections, exceptions and
file I/O. Writing that in hand-written IR (as v0.1 does) is not reviewable.
From F1 on, the runtime is C11 source shipped in the jar
(`src/main/resources/siyollvm/runtime/siyo_rt.c` + `siyo_rt.h`), and `build`
compiles it together with the program:

```
clang -O1 -fverify-intermediate-code -w -x ir prog.ll -x c -std=c11 siyo_rt.c -pthread -o exe
```

The program IR declares runtime functions (`@siyo_rt_*`) and libc; the
runtime uses libc only. `emit-llvm` keeps working without clang; its output
is the program module, and `emit-runtime <dir>` writes the runtime source so
a user can link by hand. clang is already mandatory for `build`, so this adds
no dependency. The existing stack guard, program thread, print and division
helpers move into the runtime; their observable behaviour is unchanged and
covered by the existing corpus.

### 2. Value representation

The binder's static types are partly erased (`SiyoArray`, `SiyoStruct`,
`Object`; an index expression into `object[]` has type `Object`). The JVM
backend copes through runtime classes; the native backend does the same:

- `int` → `i32`, `long` → `i64`, `bool` → `i1` when the static type says so.
- Every reference value is a `ptr` to a heap object whose first word is a
  type tag, or `null`: string, boxed int/long/bool, array, map, struct.
- A primitive stored where the static type is erased (array element, map
  value, struct field, `object` variable, thrown value) is boxed; reading it
  back at a primitive static type unboxes with a tag check that raises
  `java.lang.ClassCastException` as the JVM would.
- Strings are immutable, with an exact UTF-16 length always recorded in the
  header (layout in the next bullet), and their bytes are **WTF-8** (UTF-8
  that may hold lone surrogates), so
  every Java `String` value, including a substring that splits a surrogate
  pair, has an exact representation. All index-based operations (`len`,
  `substring`, `indexOf`, `ord`, comparison order) use UTF-16 code units as
  Java does, with an all-ASCII fast path. Output encodes lone surrogates as
  `?`, as Java's UTF-8 encoder does.
- A string header points at its bytes rather than holding them inline:
  `{tag, utf16 length, byte length, data pointer, buffer pointer}`. Literal
  headers point at constant byte arrays and have no buffer. A runtime string's
  buffer records its capacity and how many bytes are in use; `a + b` appends
  in place when `a` ends exactly at the buffer's used mark and `b` fits,
  producing a new header over the same buffer (earlier headers keep their
  own length, so strings stay immutable), and otherwise copies into a fresh
  buffer with doubled capacity. `s += x` in a loop is therefore amortised
  linear in time and memory, which never-free (below) needs.

### 3. Memory

Objects are allocated with `malloc` and never freed during a run; the
process releases everything at exit. No object can be used after free, no
pointer is ever dangling, and aliasing is plain sharing (as on the JVM). This
is the "safe ownership" choice for a batch CLI: memory use grows with the
total allocated, which is documented as a limitation with a measured figure
for the application. Allocation failure prints
`java.lang.OutOfMemoryError` and exits 1. A collector is a later roadmap
item, not part of this milestone.

### 4. Exceptions

No `setjmp`: every function that can raise returns normally with a
thread-local *pending exception* set, and the emitter checks it after each
call that can raise (Siyo calls and raising runtime calls). A pending
exception branches to the innermost enclosing `catch` of the function, or
returns the type's default to the caller, which checks in turn. A thrown
value is any value (boxed if primitive). Runtime errors raise Java-shaped
exception objects (`java.lang.ArithmeticException` with message `/ by
zero`, `java.lang.IndexOutOfBoundsException: Index 3 out of bounds for length
1`, …); `toString(e)` / string use of a caught one is its message, as in the
reference. Uncaught at `main`: stderr gets the first line the JVM prints
(`codeanalysis.SiyoThrow: boom`, `java.lang.…: message`), stdout is flushed,
exit 1. Stack overflow stays fatal (not catchable); documented.

### 5. Modules

Covered in its own section once F4 is designed against the binder's
`ModuleHandler` (see the roadmap). The constraint fixed now: `import`
resolution is done by the snapshot binder unchanged; SiyoLLVM supplies its own
`std/*` sources from its jar (an isolated adaptation, never an edit of the
snapshot), whose bodies call native runtime primitives.

## Features (vertical slices)

Each feature lands with: frontend/validator unit tests, emitter structure
tests, native corpus programs with oracle-generated expectations (plus
error-path cases), docs (`docs/SUBSET.md`, README) and a roadmap update.

- **F1 strings**: C runtime switch; `+` concatenation with any accepted type,
  interpolation, `toString`, `len`, `substring`, `indexOf`, `contains`,
  `startsWith`, `endsWith`, `replace`, `trim`, `toUpper`/`toLower`, `split`,
  `chr`, `ord`, `parseInt`/`parseLong`, `toLong`, string ordering, string
  `+=`.
- **F2 exceptions**: `try`/`catch`, `throw`, `error()`, catchable runtime
  errors (division by zero, bounds, casts, parse), uncaught formats.
- **F3 data**: arrays (`T[]`, `Array<T>`), literals, indexing, index
  assignment, `push`/`pop`/`removeAt`/`len`, `for … in` over arrays and
  `range`; structs, literals, field read/write, `impl` methods, enums;
  `Map<K,V>` with string/int keys (insertion order, `has`, `get`, `set`,
  index, `for … in` keys, `keys`), `toString` of all of these.
- **F4 modules and std**: user modules, `std/io` (`readFile`, `readLines`,
  `writeFile`, `appendFile`, `exists`, `isFile`, `eprintln`), `std/os`
  (`args`, `exit` if present in 0.9.0), `std/strings` subset.
- **F5 application** as above.
- Later (after the milestone or when the app needs them): `float`,
  sum types + `match`, lambdas / function values, sets.

## Testing and evidence

- Java gate `mvn verify` (no clang) and native gate `mvn verify -Pnative`
  (fails without clang) on the final tree before every push.
- Every new corpus expectation is produced by `scripts/reference-oracle.sh`;
  hand-written expectations are marked in the file and justified, as in v0.1.
- Required regression themes (goal doc): Unicode names/paths, UTF-8 under
  `LC_ALL=C`, embedded NUL, integer edges, short-circuit CFG, runtime errors,
  destructive output aliases; plus lifetime, aliasing, bounds,
  initialisation order and cross-module tests for new features.
- Evidence per feature in `docs/evidence/<feature>/` (gate output tails,
  remote SHA, CI run URL).

## Owner-dependent decisions (not blocking)

- A garbage collector or ownership model beyond never-free (affects
  long-running programs, not this milestone).
- Whether to upstream any frontend change; none is planned.
