# SiyoLLVM — design

Date: 2026-11-06
Status: approved by supervisor brief (`siyollvm-supervision/brief.md`); the
session is non-interactive and the brief is the approval gate.

## Intent

What was asked (brief):

- A **standalone, experimental** compiler: Siyo source → real textual LLVM IR
  → native executable (via clang). Its own CLI, artifact, docs, examples,
  tests. It is not a backend inside SiyoCompiler.
- A meaningful, documented subset: scalars, control flow, functions, printing.
- The frontend (lexer, parser, binder, lowerer) is reused from SiyoCompiler at
  the pinned commit `02c226298296fb4379f7251a9dfba19b7e887f3d` (v0.9.0) as a
  **minimal snapshot**, with provenance, attribution and an update procedure.
  No licensing terms are invented or added.
- Fresh clone must build with no sibling checkout and no network *source*
  dependency (Maven Central artifacts are fine).
- Known defects of the old prototype must be fixed with regression tests:
  Unicode parameter-name collision, embedded-NUL truncation/equality, null
  default-global string segfault.
- No silent semantic fallback and no native UB inside the advertised subset.
  Match JVM scalar semantics, or reject / document deviations. Unsupported
  bound constructs fail clearly, **including unreachable code and functions
  that are never called**.
- Default `mvn verify` needs no LLVM. A native gate (`-Pnative`) fails —
  never skips — when the required clang is missing. CI provisions LLVM and
  runs the gate as mandatory.

Assumptions taken without a round-trip:

- Target is Linux (x86_64/aarch64) with glibc. The IR declares only libc
  functions; no C source is generated or compiled.
- The reference semantics are the 0.9.0 JVM backend (`siyoc run`), probed
  from a scratch build of the pinned commit.
- clang ≥ 15 is accepted (opaque pointers); CI and local verification use
  clang 19.

## Probed reference semantics (JVM 0.9.0)

| Case | JVM result | SiyoLLVM |
|---|---|---|
| `int` / `long` `+ - *` overflow | wraps (two's complement) | same (`add`/`sub`/`mul` without `nsw`) |
| `MIN / -1`, `MIN % -1` | `MIN`, `0` | same (explicit guard; LLVM `sdiv` would be UB) |
| `x / 0`, `x % 0` (int, long) | `java.lang.ArithmeticException: / by zero` + stack trace on stderr, exit 1 | first line only on stderr, exit 1 (stack trace is a documented deviation) |
| `<<`, `>>` on int | count masked `& 31`, `>>` arithmetic | same |
| Deep recursion | `java.lang.StackOverflowError` + trace, exit 1 | `java.lang.StackOverflowError` on stderr, exit 1, via a stack-pointer guard; the depth at which it triggers differs (documented) |
| `&&` / `\|\|` | short-circuit | same |
| `println("a\0b")` | writes the NUL byte | same (strings carry a length) |
| `"a\0b" == "a\0c"` | `false` (value equality) | same (length + `memcmp`) |
| Global string read before its initialiser ran | `null`; prints `null`; `null == null` true | same (null pointer is Siyo null) |
| Non-ASCII output under `LC_ALL=C` | UTF-8 bytes | UTF-8 bytes |
| `fn main() -> int` | crashes (`NoSuchMethodError`) | rejected at compile time |

## Supported subset (v0.1)

| Area | Supported |
|---|---|
| Types | `int`→`i32`, `long`→`i64`, `bool`→`i1`, `string`→`ptr` (to an immutable `{ i64 len, [N x i8] }` constant, or null) |
| Literals | int, long, bool, string (UTF-8, embedded NUL allowed) |
| Variables | locals, parameters, file-level globals (zero/null-initialised, then initialised in source order before `init()`/`main()`) |
| Operators | everything the binder allows on int/long/bool, plus mixed int/long widening; `== !=` on strings |
| Control flow | everything the Lowerer emits: if/else, while, do-while, for, break, continue, return |
| Functions | user functions with supported parameter/return types; recursion; any order |
| Builtins | `print(x)`, `println(x)` for int, long, bool, string |
| Entry | globals, then `init()`, then `main()`, both optional, zero parameters, no return value |

Rejected with `[llvm] <construct> is not supported by SiyoLLVM`: double,
string concatenation/interpolation, string `< <= > >=` (JVM compares UTF-16
code units; not reproduced), `null` literal, arrays, maps, sets, structs,
enums, unions, match, if-expressions, lambdas/closures, try/catch/throw,
imports (`import`, `import java`), actors, scope/spawn/send, casts, every
other builtin, generics/interfaces, value-returning or parameterised
`main`/`init`.

## Architecture

Single Maven module, Java 21. Two source roots:

1. `upstream/siyocompiler/src/main/java` — verbatim, unmodified snapshot of
   the frontend closure (packages `codeanalysis.*` keep their names). Added
   with `build-helper-maven-plugin`. `upstream/siyocompiler/PROVENANCE.md`
   records commit, author attribution, why each file is included, and the
   update procedure; `upstream/siyocompiler/MANIFEST.sha256` holds a checksum
   per file. `scripts/update-upstream.sh <siyocompiler-checkout> <commit>`
   re-extracts exactly the manifest's file list with `git show` and rewrites
   the manifest. A unit test checks the snapshot matches the manifest.
   The closure was computed with `javac -implicit` from a probe that parses,
   binds and lowers: syntax, text, binding, lowering, symbols, the runtime
   type tokens the binder references (`SiyoArray` …), Java-interop metadata
   (needs ASM 9.6 at compile time), and `project/*` (needed by
   `ModuleRegistry`). Evaluator, JVM emitter, runtime, stdlib `.siyo`,
   `Main`, actors/HTTP are excluded.

2. `src/main/java/siyollvm/...` — new code:

- **`frontend.Frontend`**: reads already-decoded text; parses; if the syntax
  tree contains an import statement it reports `[llvm] imports …` and stops
  *before* binding (so binding never touches the filesystem or classpath);
  binds with `enforceTopLevel=true`; lowers the top-level block and each
  function body. Returns `FrontendResult(program | diagnostics)`.
- **`check.SubsetValidator`**: walks every lowered statement and expression
  of every function (reachable or not) and the global initialiser block,
  with a whitelist of node kinds, operator/type combinations, builtins and
  types. Every rejection is a diagnostic at the closest known source offset
  (the statement's `sourceOffset`, else the function's declaration span).
  It also validates entry-point signatures and string literals (no unpaired
  surrogates).
- **`ir.IrEmitter`**: only runs on a validated program. Any node it does not
  know throws `IllegalStateException` (an internal error, never silent
  output). Naming is index-based (`@siyo.fn.N`, `%vN`, `%tN`, `@siyo.g.N`,
  `@siyo.str.N`), so source identifiers — including Unicode ones — never
  reach LLVM identifiers. Locals are `alloca` slots. Labels become basic
  blocks; after a terminator a fresh block keeps the IR well formed.
  Runtime helpers emitted as IR: `@siyo.print_str`, `@siyo.str_eq`,
  `@siyo.div_by_zero`, `@siyo.stack_overflow`, stack guard initialisation.
- **`ir.RuntimeIr`**: the fixed IR text for the helpers and libc
  declarations (`fwrite`, `printf`, `fputc`, `fputs`, `fflush`, `exit`,
  `memcmp`, `pthread_*`).
- **Stack guard** (revised after final review): `@main` runs the program on
  a pthread with a 16 MiB stack; the thread records the address of a local
  as the stack base and sets `limit = base - 16 MiB + 256 KiB`. Every user
  function's entry compares the address of its own `alloca` marker against
  the limit and calls `@siyo.stack_overflow` when below. This turns stack
  exhaustion into a defined error rather than SIGSEGV, independent of argv,
  environment size and `ulimit -s`.
- **`toolchain.Clang`**: resolves clang from `--clang`, then
  `$SIYOLLVM_CLANG`, then `clang` on `PATH`; checks the major version ≥ 15;
  runs `clang -O1 -fverify-intermediate-code -w -x ir <ll> -pthread -o <exe>`; reports clear errors.
- **`cli.Main`**: `siyollvm emit-llvm <file> [-o out.ll]`,
  `siyollvm build <file> [-o exe] [--clang path] [--emit-llvm out.ll]`,
  `--help`, `--version`. Source read as strict UTF-8 (malformed input is a
  clear error). stdout/stderr are UTF-8 regardless of locale. Diagnostics
  format: `file(line, col): message`. Exit 1 on any error; no output file
  is written when there are diagnostics.

Artifact: shaded `target/siyollvm-<version>.jar` and launcher `bin/siyollvm`.

## Error handling

- Syntax / binding diagnostics: printed as-is, exit 1.
- Subset violations: all reported in one run, exit 1, no IR.
- Emitter internal error: `internal error: …`, exit 2.
- Clang missing / too old / failing: message names the lookup order, exit 1.

## Testing

- JUnit 5, default `mvn verify` (no clang): lexer-to-IR unit tests on IR
  text, validator tests (one per rejected construct, including unreachable
  and uncalled code), naming tests (Unicode params), runtime-IR tests, CLI
  tests, snapshot manifest test.
- Native gate (`mvn verify -Pnative`, JUnit tag `native`): every
  `examples/*.siyo` and `src/test/resources/native/*.siyo` is built with
  clang and run; stdout, stderr and exit code must equal the
  `.expected`/`.expected-stderr`/`.expected-exit` files (expected output
  generated from the JVM 0.9.0 reference; deviations noted next to the
  case). Also runs one case with `LC_ALL=C`. Missing clang ⇒ test failure.
- Evidence of red/green TDD runs saved under
  `siyollvm-supervision/worker-evidence/`.
- CI: GitHub Actions on ubuntu-24.04 installs `clang-19` via apt, runs
  `mvn -B verify` then `mvn -B verify -Pnative` with
  `SIYOLLVM_CLANG=clang-19`.
