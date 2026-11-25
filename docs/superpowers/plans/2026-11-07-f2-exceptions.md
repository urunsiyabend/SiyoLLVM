# F2 — `any` values and exceptions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compile `any`/`object` values (boxed, tagged) and `throw`, `try`/`catch` (statement and expression) and `error()`, with runtime errors catchable and uncaught errors reported as the JVM's first line.

**Architecture:** An `any` value is a `ptr` to a tagged heap object (string, boxed int/long/bool) or null; conversions box and check-unbox. Exceptions use explicit propagation: a raise sets a pending-exception record in the runtime, and the emitter tests it after every call that can raise, branching to the innermost lexical `catch` of the function or returning the type's default. `siyo_program` reports an uncaught exception and exits 1.

**Tech Stack:** Java 21, Maven, JUnit 5, C11 runtime, clang ≥ 15.

**Spec:** `docs/superpowers/specs/2026-11-07-native-toolchain-design.md` (§2 value representation, §4 exceptions, F2). Frontend facts: `docs/superpowers/specs/2026-11-07-frontend-survey.md` §4 (try/throw nodes, lowering caveats) and §5 (catch payloads, uncaught format).

## Global Constraints

- Reference semantics: `siyoc run` 0.9.0 (`02c226298296fb4379f7251a9dfba19b7e887f3d`); every corpus expectation comes from `scripts/reference-oracle.sh run`; stderr expectation is the JVM's first line only.
- No `setjmp`/`longjmp`; no C++ exceptions; propagation is explicit in IR.
- Stack overflow stays fatal and uncatchable (documented deviation: the JVM catches it with payload `StackOverflowError`).
- stdout is flushed before any stderr error line; uncaught exit status is 1.
- The snapshot under `upstream/` is never edited. Index-based IR names only.
- Native gate fails, never skips, without clang. Runtime C warning-free under `-std=c11 -Wall -Wextra`.

## Review Focus

- `break`/`continue`/`return` from inside a `try` body or `catch` body jumping to labels outside it: the handler is lexical, so nothing to unwind — test `try_control_flow` (Task 2) loops with `continue` in a catch and `break`/`return` in a try.
- An exception raised while evaluating a global initialiser or inside `init()`: uncaught report and exit 1, later globals not run — test `throw_in_global_init` (Task 2).
- A raise in the right operand of `&&`/`||` and inside a call argument list: earlier side effects happen, later arguments are not evaluated — test `throw_mid_expression` (Task 2).
- Rethrowing a caught runtime error (`throw e`) reports `codeanalysis.SiyoThrow: <message>`, not the original Java class — test in `catch_payloads` (Task 2).
- A function that raises but whose value is used in an `any`/boxing context: no use of an undefined value (the default is returned and never observed) — IR verifier plus test `throw_through_any` (Task 2).

---

### Task 1: `any` values

**Files:**
- Modify: `src/main/java/siyollvm/check/SubsetValidator.java`, `src/main/java/siyollvm/ir/IrEmitter.java`, `src/main/java/siyollvm/ir/LlvmTypes.java`, `src/main/java/siyollvm/ir/RuntimeIr.java`, `src/main/resources/siyollvm/runtime/siyo_rt.{c,h}`
- Test: `SubsetValidatorTest`, `IrEmitterTest`; corpus `any_values.siyo`, `any_cast_error.siyo` (+ oracle expectations)

**Interfaces:**
- C: `typedef struct SiyoBox { uint32_t tag; int64_t value; } SiyoBox;` with tags `SIYO_TAG_INT=2`, `SIYO_TAG_LONG=3`, `SIYO_TAG_BOOL=4`; `void *siyo_rt_box_int(int32_t)`, `siyo_rt_box_long(int64_t)`, `siyo_rt_box_bool(_Bool)`; `int32_t siyo_rt_unbox_int(const void*)`, `int64_t siyo_rt_unbox_long(const void*)`, `_Bool siyo_rt_unbox_bool(const void*)`, `const SiyoStr *siyo_rt_cast_str(const void*)` — a wrong tag raises `java.lang.ClassCastException` with the JVM message, e.g. `class java.lang.Integer cannot be cast to class java.lang.String (java.lang.Integer and java.lang.String are in module java.base of loader 'bootstrap')`; unboxing null raises `java.lang.NullPointerException` (take exact first lines from the oracle). `SiyoStr *siyo_rt_any_to_string(const void*)` (`String.valueOf`), `_Bool siyo_rt_any_equals(const void*, const void*)` (`Objects.equals`: same tag and value; `5L == 5` is false), print via `siyo_rt_print_any`.
- Java: `LlvmTypes.of(Object.class)` = `ptr`, zero `null`. Every value of static type `Object` is a `ptr`.
- Validator: accepts `Object` as variable, parameter and return type; `BoundConversionExpression` int/long/bool/string → `Object` (box; string is already a tagged pointer) and `Object` → int/long/bool/string (checked unbox/cast); `==`/`!=` with an `Object` operand; `+` with an `Object` operand (string concatenation); `toString`/`print`/`println` of `Object`; the `null` literal where the binder types it as `Object` or `String`.

- [ ] **Step 1:** Validator tests for each accepted form above; `mut x: any = 1.5` still reports `float values are`.
- [ ] **Step 2:** Corpus `any_values` (assign int/long/bool/string/null to an `any` variable, pass through `fn f(x: any) -> any`, print, concatenate, compare `a == "s"`, `5L == 5` is false, unbox back into int/string) and `any_cast_error` (`imut s: string = f(3)` after printing a line). Oracle expectations.
- [ ] **Step 3:** Run, expect FAIL. **Step 4:** Implement. **Step 5:** Both gates pass. **Step 6: Commit** `feat: any values with boxing and checked casts`.

### Task 2: throw, try/catch, error(), catchable runtime errors

**Files:**
- Modify: `SubsetValidator.java`, `IrEmitter.java`, `RuntimeIr.java`, `siyo_rt.{c,h}`
- Test: `SubsetValidatorTest`, `IrEmitterTest` (pending check after calls; structure via `IrStructure.assertWellFormed`); corpus `throw_uncaught_string.siyo`, `throw_uncaught_int.siyo`, `throw_uncaught_null.siyo`, `catch_payloads.siyo`, `try_control_flow.siyo`, `throw_in_global_init.siyo`, `throw_mid_expression.siyo`, `throw_through_any.siyo`, `error_builtin.siyo`, and update `div_zero_int`, `div_zero_long`, `rem_zero`, `substring_range` only if their expectations change (they should not: uncaught output is identical)

**Interfaces:**
- C: `void siyo_rt_throw(void *payload)` (Siyo `throw`/`error()`: kind SiyoThrow), `void siyo_rt_raise_java(const char *java_class, const SiyoStr *message)` now **sets pending and returns** (no exit); `_Bool siyo_rt_pending(void)` (or an external global `siyo_rt_pending_flag` the IR loads); `void *siyo_rt_catch(void)` — returns the catch payload (raw payload for SiyoThrow; for a Java exception its message string, or the simple class name, e.g. `ArithmeticException`, when the message is null) and clears pending; `_Noreturn void siyo_rt_uncaught(void)` — flush stdout, print `codeanalysis.SiyoThrow: <String.valueOf(payload)>` or `<java_class>: <message>` (`<java_class>` alone when the message is null), exit 1.
- `siyo_rt_div_by_zero` raises `java.lang.ArithmeticException` / `/ by zero` and returns; the IR continues to the pending check. F1's raising functions (`substring`, `parseLong`, `ord`, null receivers) now return a dummy value after raising.
- IR contract: after every `call` to a Siyo function or to a runtime function listed as raising in `RuntimeIr` (a `RAISING` set there), emit `br i1 <pending>, label %<handler>, label %<next>`. `<handler>` is the innermost enclosing catch block of the current function, or a per-function block returning the type's default. Inside `@siyo_program`, `@siyo.init_globals`, `init` and `main`, the outermost handler calls `@siyo_rt_uncaught`.
- `throw <expr>`: evaluate, box to `ptr`, call `@siyo_rt_throw`, branch to the handler. Catch variable: bound as `Object` (or the annotated type, through the binder's conversion), initialised from `@siyo_rt_catch()`.
- The validator accepts `BoundTryCatchStatement` (validating both bodies recursively, including nested blocks, labels and gotos, which the lowerer leaves structured), `BoundThrowStatement`, and the `ERROR` builtin.

- [ ] **Step 1:** Validator tests (try/catch, throw of each value type, `error()`, nested try in catch); emitter test: a call inside `try` branches to the catch block; outside, to the default-return block.
- [ ] **Step 2:** Corpus programs listed above, covering: throw string/int/null uncaught; catch of `/ by zero`, substring range, parseLong; throw across three call levels; nested try; rethrow (`codeanalysis.SiyoThrow: ...`); `catch e: string`; continue/break/return inside try and catch; raise in a global initialiser and in `init()`; raise in an argument list and in `&&`'s right operand; a raising call whose result is boxed. Oracle expectations.
- [ ] **Step 3:** Run, expect FAIL. **Step 4:** Implement. **Step 5:** Both gates pass; v0.1 corpus unchanged. **Step 6: Commit** `feat: throw, try/catch and catchable runtime errors`.

### Task 3: try expressions

**Files:** `SubsetValidator.java`, `IrEmitter.java`; corpus `try_expression.siyo`.

**Interfaces:** `BoundTryExpression(tryBody, errorVariable, catchBody, type)` — both bodies are already lowered by the binder; the value is the last expression statement of the body that ran, stored into a slot of the expression's type and loaded at the join block. Consumes Task 2's handler mechanism.

- [ ] **Step 1:** Validator test; corpus `try_expression` (`imut v = try { parseLong("zz") } catch e { 0L - 1L }`, a try expression whose body succeeds, one whose value is a string, one nested in a function argument). Oracle expectations.
- [ ] **Step 2:** Run, expect FAIL. **Step 3:** Implement. **Step 4:** Both gates pass. **Step 5: Commit** `feat: try expressions`.

### Task 4: Docs, evidence, review, push

- [ ] SUBSET.md (accepted: `any`, throw/try/catch/error; runtime errors catchable with their payloads; deviations: stack overflow not catchable, NPE/CCE wording measured), ROADMAP row, `docs/evidence/f2-exceptions/` gate tails; independent whole-feature review; fixes; final gates; push; record remote SHA and CI URL.
