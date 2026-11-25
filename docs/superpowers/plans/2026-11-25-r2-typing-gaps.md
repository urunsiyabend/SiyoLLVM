# R2 — Erased-value (`any`) typing gaps met by applications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Some programs that the JVM reference accepts and runs, and that real applications write, are rejected by SiyoLLVM today. Accept them and run them with the JVM's exact semantics. The application-exposed cases are:

- array elements returned from another module's function (`imut f = csv.cols(line); parseInt(f[1])`);
- struct values read through a map local whose type was inferred from a struct field (`imut accounts = book.accounts; imut a: Account = accounts[k]; a.count += 1`);
- related uses of such erased values in arguments, operators and member access.

**Architecture:**
- **Why these values are `any`.** The binder erases these values to `Object`. The JVM backend still runs the program because the bytecode it emits checks and converts erased values where they are used: `checkcast`, unboxing, and dynamic member access on `SiyoObject`.
- **Decide case by case with the oracle.** For every construct, measure the reference's actual behaviour with the oracle on an accepted case, a wrong-type case and a null case.
  - If the reference runs the construct, implement the same check at that use site in SiyoLLVM, using F2's checked casts and unboxing with exact exception messages.
  - If the reference fails verification or rejects the construct, keep the current SiyoLLVM rejection.
- **What may and may not change.** No change to the binder snapshot. Use-site checks in the validator and emitter only. The value representation does not change.

**Tech Stack:** Java 21 (validator/emitter), C11 runtime (only if a new checked operation is needed), JUnit 5, JVM reference oracle.

**Spec:** `docs/RUNTIME_READINESS_GOAL.md` (outcome 2). Earlier decisions: `docs/SUBSET.md` documents the gaps and records that constructs where the reference fails with VerifyError are rejected (F2 rulings).

## Global Constraints

- The reference oracle decides. Every new expectation comes from `scripts/reference-oracle.sh`. A construct is accepted only when an oracle case shows the reference runs it. Wrong-type and null behaviour must match the oracle's first stderr line, or be a documented deviation of the existing kind (NPE "because …" text).
- No existing diagnostic for a construct the reference cannot run is removed.
- Host rule: plain `mvn` is the bounded wrapper. The oracle, clang and binaries run through `python3 ~/.hermes/automation/siyollvm/bounded.py --`. Scratch files go under `$TMPDIR` only.
- The GC stress suite must still pass. New corpus and module cases are picked up by `GcStressNativeTest` automatically. If a new case allocates nothing, add it to `NO_ALLOCATION` with a reason.
- Commits are authored by urunsiyabend@gmail.com.

## Review Focus

- **Wrong runtime type at each newly accepted use site.** The test must show the exact JVM `ClassCastException` message. Test `erased_casts` (Task 2).
- **Null at each newly accepted unboxing site.** The test must show the JVM's `NullPointerException` first line or the documented deviation. Test `erased_nulls` (Task 2).
- **Cross-module.** A struct and an array returned from module A, read in module B through a map field, then passed back to module A. Test the `modules_erased` directory case (Task 2).
- **Ledger.** Remove the workarounds that R2 makes unnecessary from `projects/ledger` without changing any output. All 27 cases must still pass against both oracles (Task 3).

---

### Task 1: Probe and RED cases

- [ ] **Step 1:** Write a probe matrix under `$TMPDIR` covering every erased-use construct applications hit:
  - `any` as an argument to string builtins and to user functions with typed parameters;
  - `any` in `+`, `-`, `*`, `/`, `<`, `==` and `+=`, with int, long and string;
  - member read, write and `+=` on a value typed `any` but declared as a struct;
  - `.method()` on such a value;
  - indexing an `any` that holds an array or a map;
  - `for … in` over such a value;
  - `len` on such a value.

  Run each case through the oracle three ways: correct type, wrong type, null. Record a table with columns: construct, reference behaviour (runs / CCE text / NPE text / VerifyError / bind error), and SiyoLLVM today (accepts / rejects / differs).
- [ ] **Step 2:** For every construct the reference runs, add a corpus or module case with oracle expectations covering the correct-type, wrong-type and null runs. These cases fail today with "not supported" (RED). Commit the table to `docs/evidence/r2-typing/probe-matrix.md`, plus the RED log.
- [ ] **Step 3: Commit** `test: erased-value use sites the reference runs (RED)`. Do not push: the native gate is red until Task 2.

### Task 2: Use-site checks

- [ ] **Step 1:** In SubsetValidator and IrEmitter, accept each probed construct the reference runs. Emit the same checked cast, unbox or dynamic access at the use site:
  - string arguments go through `siyo_rt_cast_str`;
  - int and long operands go through the unbox functions;
  - member access on an `any` resolved as a struct goes through `siyo_rt_cast_struct` and then the field lookup by name.

  Exception messages must match the oracle.
- [ ] **Step 2:** All Task 1 cases pass. Existing rejections for constructs the reference cannot run keep their diagnostics, and tests pin them.
- [ ] **Step 3:** Both gates pass, including GcStress.
- [ ] **Step 4: Commit** `feat: JVM checked casts at erased use sites the reference accepts`.

### Task 3: Ledger simplification, docs, review, publish

- [ ] **Step 1:** Remove from `projects/ledger` the workarounds that R2 makes unnecessary. The outputs must not change: run both oracles and the native app, 27 out of 27.
- [ ] **Step 2:** Update `docs/SUBSET.md` (the remaining gaps, with the probe table) and the ROADMAP.
- [ ] **Step 3:** Run the whole-feature review and one fix wave.
- [ ] **Step 4:** Final gates, then a fresh remote clone, then publish, then check CI on the exact head.
