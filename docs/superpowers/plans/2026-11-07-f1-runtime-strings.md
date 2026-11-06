# F1 — C runtime and strings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the runtime to C compiled by clang beside the program IR, give strings a tagged WTF-8 representation, and compile string concatenation, interpolation, ordering and the string builtins with JVM-exact results.

**Architecture:** `siyo_rt.c`/`siyo_rt.h` ship as jar resources; `Clang.compile(ll, exe)` writes them to a temporary directory and passes `-x c` after the IR. The IR emitter calls `siyo_rt_*` functions; the validator whitelists the new operators and builtins. Runtime errors are fatal in F1 through one function, `siyo_rt_raise_java`, which F2 turns into a catchable raise.

**Tech Stack:** Java 21, Maven, JUnit 5, C11, clang ≥ 15 (19 in CI).

**Spec:** `docs/superpowers/specs/2026-11-07-native-toolchain-design.md` (sections 1, 2, 3; F1).

## Global Constraints

- Reference semantics: `siyoc run` 0.9.0 built from `02c226298296fb4379f7251a9dfba19b7e887f3d`; every new corpus expectation is produced by `scripts/reference-oracle.sh run`, never hand-written (exceptions marked in the file).
- clang command: `clang -O1 -fverify-intermediate-code -w -x ir <prog.ll> -x c -std=c11 <siyo_rt.c> -pthread -o <exe>`; the runtime uses libc only.
- The snapshot under `upstream/` is never edited.
- Native gate fails, never skips, without clang. Java gate needs no clang.
- Program stack 16 MiB, guard headroom 256 KiB, unchanged.
- No source identifier reaches the IR (index-based names stay).
- Commits authored `urunsiyabend@gmail.com`, ending with the Claude co-author line.

## Review Focus

- Concatenation producing a string that is later sliced across a surrogate pair, then re-joined: must re-pair into one 4-byte sequence (WTF-8 normalisation) and print as the original character. Test: `surrogate_rejoin` corpus case (Task 4).
- `null` string global used in `+`, `len`, comparisons before its initialiser ran: `+` gives `"null"`, `==` works; `len(null)` raises. Test: `null_string_ops` corpus case (Task 3).
- Embedded NUL inside strings passed through every builtin (`indexOf`, `replace`, `trim`, `split` not in F1): bytes preserved; `trim` strips `\0` because Java trims `<= ' '`. Test in `string_builtins` (Task 4).
- `substring`/`chr`/`ord` index errors: JVM first line, exit 1, stdout flushed first. Test: `substring_range` corpus case (Task 4).
- Very long concatenation in a loop (100 000 appends): finishes under the 60 s run timeout and does not overflow the stack. Test: `concat_loop` corpus case (Task 3).

---

### Task 1: C runtime linked by clang; existing behaviour preserved

**Files:**
- Create: `src/main/resources/siyollvm/runtime/siyo_rt.h`, `src/main/resources/siyollvm/runtime/siyo_rt.c`
- Create: `src/main/java/siyollvm/toolchain/RuntimeSource.java`
- Modify: `src/main/java/siyollvm/toolchain/Clang.java` (`compile`), `src/main/java/siyollvm/ir/RuntimeIr.java` (reduce to declarations), `src/main/java/siyollvm/ir/IrEmitter.java` (entry point, helpers), `src/main/java/siyollvm/ir/StringConstants.java` (new literal layout)
- Test: `src/test/java/siyollvm/toolchain/ClangTest.java`, `src/test/java/siyollvm/toolchain/RuntimeSourceTest.java`, `src/test/java/siyollvm/ir/IrEmitterTest.java`; all existing native tests

**Interfaces:**
- Produces (C, `siyo_rt.h`):
  - `typedef struct SiyoStr { uint32_t tag; int32_t utf16_len; int64_t len; char bytes[]; } SiyoStr;` with `SIYO_TAG_STR = 1`; tags 2–4 reserved for boxed int/long/bool, 5 array, 6 map, 7 struct, 8 exception.
  - `extern uint64_t siyo_rt_stack_limit;`
  - `void siyo_rt_print_int(int32_t)`, `siyo_rt_print_long(int64_t)`, `siyo_rt_print_bool(_Bool)`, `siyo_rt_print_str(const SiyoStr*)` (null prints `null`; lone surrogates print `?`), `siyo_rt_print_newline(void)`
  - `_Bool siyo_rt_str_eq(const SiyoStr*, const SiyoStr*)`
  - `void siyo_rt_div_by_zero(void)`, `void siyo_rt_stack_overflow(void)` — noreturn in F1
  - `void siyo_rt_raise_java(const char *java_class, const SiyoStr *message)` — F1: flush stdout, write `<java_class>: <message>\n` (or `<java_class>\n` when message is null) to stderr, exit 1.
  - `void *siyo_rt_alloc(size_t)` — malloc; on failure prints `java.lang.OutOfMemoryError` and exits 1. Never freed.
  - `int main(int argc, char **argv)`: stores args (`siyo_rt_argc/argv`), runs `void siyo_program(void)` (defined by the IR, external) on a 16 MiB pthread whose first act is setting `siyo_rt_stack_limit` = its frame address − 16 MiB + 256 KiB, joins, `fflush(NULL)`, returns 0. Thread failure message unchanged: `SiyoLLVM runtime: cannot start the program thread`.
- Produces (Java): `RuntimeSource.writeTo(Path directory) -> Path` (writes both files, returns the `.c` path); `Clang.compile(Path ll, Path exe)` keeps its signature.
- IR: string literals become `{ i32 1, i32 <utf16 length>, i64 <byte length>, [n x i8] }` constants, `align 8`.

- [ ] **Step 1: Failing tests.** `RuntimeSourceTest.writesRuntimeFiles` (both files exist, `.c` contains `int main(`). `ClangTest`: the recorded clang argv equals `[-O1, -fverify-intermediate-code, -w, -x, ir, <ll>, -x, c, -std=c11, <dir>/siyo_rt.c, -pthread, -o, <exe>]` and the temp runtime directory is gone after `compile` returns (both on success and on clang failure). `IrEmitterTest.literalLayout`: `println("é")` emits `{ i32 1, i32 1, i64 2, [2 x i8] c"\C3\A9" }`; `IrEmitterTest.noRuntimeBodiesInIr`: IR has no `define` other than program functions, `@siyo.init_globals` and `@siyo_program`.
- [ ] **Step 2:** Run `mvn -q test -Dtest='RuntimeSourceTest,ClangTest,IrEmitterTest'`; expect the new tests to fail.
- [ ] **Step 3: Implement.** Port every helper from `RuntimeIr` to C with the same observable output (`printf("%d")`/`("%lld")`, `fwrite` of string bytes); `RuntimeIr.text()` keeps only `declare` lines and `@siyo_rt_stack_limit = external global i64`. `IrEmitter.entryPoint()` emits `define void @siyo_program()` (init globals, `init()`, `main()`), no `@main`. Function prologues keep the guard, loading `@siyo_rt_stack_limit` and calling `@siyo_rt_stack_overflow`. `Clang.compile` creates `Files.createTempDirectory(exe parent, ".siyo-rt")`, writes the runtime, compiles, deletes the directory in `finally`.
- [ ] **Step 4:** `mvn -q verify` and `SIYOLLVM_CLANG=… mvn -q verify -Pnative`: everything passes, including the whole v0.1 corpus and `StackGuardNativeTest`, unchanged expectations.
- [ ] **Step 5: Commit** `refactor: C runtime compiled by clang beside the program IR`.

### Task 2: `emit-runtime` command

**Files:** Modify `src/main/java/siyollvm/cli/Main.java`; Test `src/test/java/siyollvm/cli/MainTest.java`; docs `README.md`.

**Interfaces:** Consumes `RuntimeSource.writeTo`. CLI: `siyollvm emit-runtime <directory>` writes `siyo_rt.c` and `siyo_rt.h` (creating the directory), exit 0; missing argument is a usage error (exit 2). `--help` lists it.

- [ ] **Step 1:** Tests `emitRuntimeWritesBothFiles`, `emitRuntimeWithoutDirectoryIsUsageError` (exit 2).
- [ ] **Step 2:** Run, expect FAIL.
- [ ] **Step 3:** Implement; README "Usage" documents linking by hand: `clang -x ir prog.ll -x c siyo_rt.c -pthread`.
- [ ] **Step 4:** `mvn -q test -Dtest=MainTest` passes.
- [ ] **Step 5: Commit** `feat(cli): emit-runtime writes the C runtime for manual linking`.

### Task 3: Concatenation, interpolation, `toString`, `+=` on strings

**Files:** Modify `SubsetValidator.java`, `IrEmitter.java`, `siyo_rt.c/.h`; Test `SubsetValidatorTest.java`, `IrEmitterTest.java`; Create corpus cases under `src/test/resources/native/`: `string_concat.siyo`, `interpolation.siyo`, `null_string_ops.siyo`, `concat_loop.siyo` (+ oracle expectation files).

**Interfaces:**
- C: `SiyoStr *siyo_rt_str_concat(const SiyoStr *a, const SiyoStr *b)` (null operand reads as `null`; joins a trailing lone high surrogate with a leading lone low surrogate into one 4-byte sequence); `SiyoStr *siyo_rt_str_from_int(int32_t)`, `siyo_rt_str_from_long(int64_t)`, `siyo_rt_str_from_bool(_Bool)`; `SiyoStr *siyo_rt_str_of_string(const SiyoStr*)` (null → `"null"`, used by `toString(string)`).
- Validator: accepts `Addition` when either operand is `string` and the other is int/long/bool/string; accepts the `TO_STRING` builtin on those types; `+=` on a string variable (the binder lowers it to an assignment of `Addition`).

- [ ] **Step 1:** Validator tests: `"a" + 1`, `1 + "a"`, `"a" + true`, `"x$y"`, `s += "z"` validate cleanly; `"a" + 1.5` still reports `float values are`. Emitter test: `"a" + 1` calls `@siyo_rt_str_from_int` then `@siyo_rt_str_concat`.
- [ ] **Step 2:** Write the four corpus programs: mixed-type concatenation incl. `int` MIN/MAX and `long` MIN/MAX, left-associativity (`1 + 2 + "x"` is `3x`, `"x" + 1 + 2` is `x12`), interpolation with `$name` and `${expr}` and `\$`, Unicode and embedded NUL operands; `null_string_ops` reads a global string before its initialiser (via a function called from an earlier global's initialiser, as `null_global_string.siyo` does) in `+`, `==`, `toString`; `concat_loop` appends 100 000 single characters and prints the final `len` and the last 10 characters (`len`/`substring` arrive in Task 4; put `concat_loop` in Task 4 if needed). Generate expectations: `scripts/reference-oracle.sh run src/test/resources/native/<case>.siyo`.
- [ ] **Step 3:** Run validator/emitter tests and the native corpus, expect FAIL.
- [ ] **Step 4:** Implement. `toString(int)` uses Java's decimal form (`%d`/`%lld`), `toString(bool)` `true`/`false`.
- [ ] **Step 5:** Java and native gates pass.
- [ ] **Step 6: Commit** `feat: string concatenation, interpolation and toString`.

### Task 4: String builtins, ordering, conversions

**Files:** Modify `SubsetValidator.java`, `IrEmitter.java`, `siyo_rt.c/.h`; Test `SubsetValidatorTest.java`; Create corpus cases `string_builtins.siyo`, `string_unicode.siyo`, `string_order.siyo`, `string_parse.siyo`, `substring_range.siyo`, `surrogate_rejoin.siyo`, `case_mapping.siyo` (+ oracle expectations).

**Interfaces (C; all indices are UTF-16 code units):**
- `int32_t siyo_rt_str_len(const SiyoStr*)`
- `SiyoStr *siyo_rt_str_substring(const SiyoStr*, int32_t begin, int32_t end)` — out of range raises `java.lang.StringIndexOutOfBoundsException`; the probed JDK words it `Range [2, 9) out of bounds for length 3` (the survey's `begin B, end E, length L` is older-JDK wording). Take the exact first line from the oracle output of `substring_range` and note the JDK version in the corpus README.
- `int32_t siyo_rt_str_index_of(const SiyoStr*, const SiyoStr*)`; `_Bool siyo_rt_str_contains/starts_with/ends_with(const SiyoStr*, const SiyoStr*)`
- `SiyoStr *siyo_rt_str_replace(const SiyoStr*, const SiyoStr *target, const SiyoStr *replacement)` — literal, all occurrences, Java's empty-target behaviour (inserts between every UTF-16 unit).
- `SiyoStr *siyo_rt_str_trim(const SiyoStr*)` — strips units `<= U+0020` at both ends.
- `SiyoStr *siyo_rt_str_to_upper(const SiyoStr*)`, `siyo_rt_str_to_lower(const SiyoStr*)` — per code point via `towupper_l`/`towlower_l` in a `newlocale(LC_CTYPE_MASK, "C.UTF-8", 0)` locale, plus Java's special cases `ß` → `SS` (upper) and `İ` → `i̇` (lower). Any remaining difference found by `case_mapping` is listed in `docs/SUBSET.md` deviations with the measured characters.
- `SiyoStr *siyo_rt_chr(int32_t)` (Java `String.valueOf((char) c)`: one UTF-16 unit, lone surrogates allowed); `int32_t siyo_rt_ord(const SiyoStr*)` (first UTF-16 unit; empty string raises as Java `charAt(0)` does — wording from the oracle).
- `int32_t siyo_rt_str_compare(const SiyoStr*, const SiyoStr*)` — Java `compareTo` sign over UTF-16 units; used for `< <= > >=`.
- `int32_t siyo_rt_parse_int(const SiyoStr*)` — Java `Integer.parseInt` (optional `+`/`-`, decimal digits incl. non-ASCII Unicode digits as Java's `Character.digit` accepts them — pin with the oracle; no trimming) returning **0** for any invalid or overflowing input (`SiyoRuntime.safeParseInt`). `int64_t siyo_rt_parse_long(const SiyoStr*)` — `Long.parseLong`; invalid input raises `java.lang.NumberFormatException` with Java's message (`For input string: "x"`; take exact wording, including the empty-string and null forms, from the oracle). See `docs/superpowers/specs/2026-11-07-frontend-survey.md` §5.
- `toLong(int)` is `sext`; `toInt(long)` is `trunc` (check `BuiltinFunctions.TO_INT_LONG` semantics in the reference first); `toInt(string)` = `parseInt`.
- Null receiver in any of these raises `java.lang.NullPointerException` (first line only; documented deviation if the JVM prints a helpful message).

- [ ] **Step 1:** Validator tests whitelisting `LEN` (string argument only), `SUBSTRING`, `CONTAINS`, `INDEX_OF`, `STARTS_WITH`, `ENDS_WITH`, `REPLACE`, `TRIM`, `TO_UPPER`, `TO_LOWER`, `CHR`, `ORD`, `PARSE_INT`, `PARSE_LONG`, `TO_LONG`, `TO_INT_LONG`, `TO_INT_STR`, and string `< <= > >=`; `split` remains rejected until F3.
- [ ] **Step 2:** Corpus programs covering: ASCII, 2-, 3- and 4-byte characters, emoji indexes (`len("çağ😀x") == 6`), substring splitting a surrogate pair and re-joining it (`surrogate_rejoin`), embedded NUL in every builtin, empty strings everywhere, ordering across the BMP/supplementary boundary (`"￿"`-range vs emoji), parse edge cases (`""`, `"-"`, `"+5"`, `" 1"`, `"2147483647"`, `"2147483648"`, `"-2147483648"`, `"9223372036854775808"`), `trim` with `\0` and tabs, case mapping over a fixed list of Latin, Turkish, Greek, German and Cyrillic letters. `substring_range` ends with an out-of-range call after printing a line. Generate all expectations with the oracle.
- [ ] **Step 3:** Run, expect FAIL.
- [ ] **Step 4:** Implement the C functions and emitter dispatch.
- [ ] **Step 5:** Both gates pass; every expectation matches byte for byte under `LC_ALL=C`.
- [ ] **Step 6: Commit** `feat: string builtins with UTF-16 indexing and Java ordering`.

### Task 5: Docs, evidence, review, push

**Files:** `docs/SUBSET.md`, `README.md`, `docs/ROADMAP.md`, `docs/evidence/f1-strings/`.

- [ ] **Step 1:** SUBSET.md: move concatenation, interpolation, ordering and the builtins to Accepted; describe the string representation, never-free memory, NPE and case-mapping deviations measured in Task 4; Platform section names the C runtime. README: requirements and the new clang command.
- [ ] **Step 2:** Final-tree gates: `mvn -B verify 2>&1 | tail -40 > docs/evidence/f1-strings/java-gate.txt`; `SIYOLLVM_CLANG=… mvn -B verify -Pnative 2>&1 | tail -60 > docs/evidence/f1-strings/native-gate.txt`; `scripts/check-native-gate-requires-clang.sh` output too.
- [ ] **Step 3:** Independent review of the feature diff (fresh reviewer, code only); fix blockers; rerun Step 2.
- [ ] **Step 4:** Commit, push, record remote SHA + CI run URL in `docs/evidence/f1-strings/README.md` and the roadmap row (follow-up commit).
