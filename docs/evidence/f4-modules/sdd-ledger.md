# SDD ledger — plan: docs/superpowers/plans/2026-11-07-f4-modules.md
Spec: docs/superpowers/specs/2026-11-07-native-toolchain-design.md (§5)

## Preflight scan
| Pair / task | Produces vs consumes | Finding |
|---|---|---|
| T1→T2 | multi-unit LoweredProgram + directory harness; T2 extends harness with args/files and adds classpath std modules | consistent |
| T1 self | identity maps required (FunctionSymbol.equals ignores scope) | consistent |
| T2 self | listDir order may be nondeterministic — plan allows sort/reject with docs | consistent |
| T3 self | consistent |
Probe: $HOME/.hermes/cache/scratch/siyollvm-probes/f4/proj (oracle output recorded in plan Global Constraints)
Task 1: implementer DONE_WITH_CONCERNS at edafc1a
Ruling: qualified→module symbol mapping by key lookup (owner class, method name, arity / owner, field) instead of a precomputed identity map — collecting module-internal cross-module symbols needs a full tree walk the upstream walker can't do — cost if wrong: mis-mapping would surface as wrong output in modules_same_names/diamond tests
Ruling: uncaught error in module top-level/init prints java.lang.ExceptionInInitializerError (oracle first line) via new siyo_rt_uncaught_in_initializer — cost if wrong: none vs JVM
Ruling: module diagnostics show path relative to the main file's dir (siyoc shows bare name) — more useful; documented deviation — cost if wrong: cosmetic
Task 1: review Needs fixes — Important: two module files sharing a JVM class name are silently mixed (functions from one, globals from other)
Ruling: also add the suggested guard rejecting an import that resolves to no registry entry (silently skipped unreadable module) — rejecting is within the subset rule, silent drop of a module's init is not — cost if wrong: a program the reference runs (with a broken import) is rejected
Task 1: fix round 1/5 (4 addressed, 0 open; commits edafc1a..b42fbad)
Task 1: minor (deferred): circular import gets an extra "could not be compiled" line before the binder's message
Task 1: minor (deferred): copied moduleClassName must track upstream on snapshot update (note in PROVENANCE/SUBSET at publish)
Task 1: complete (commits dee35af..b42fbad, review clean after fix round)
NEXT: Task 2 (std/io, std/os, std/strings)
Task 2: implementer DONE at 00d9ab5
Ruling: intrinsics only for shipped classpath std (dev-mode CWD copy only if byte-identical) — no substitution of foreign code — cost if wrong: none
Ruling: listDir sorted by bytes (reference order is filesystem-dependent); documented deviation
Ruling: path/arg/env encoding follows the JVM's locale choice (ASCII under LC_ALL=C → '?'/U+FFFD, UTF-8 under UTF-8 locales) — reference compatibility; MUST add Unicode-path coverage under a UTF-8 locale (goal requirement) — cost if wrong: lossy paths under C locale, as on the JVM
Task 2: review Needs fixes (F5–F7 truncated decode count; ?? vs ? for supplementary names under C; no UTF-8-locale Unicode path coverage) → fix round 1 at 04f9dca
Task 2: fix round 1/5 (6 addressed, 0 open; commits 00d9ab5..04f9dca)
Task 2: complete (commits f0a22bd..04f9dca, review clean after fix round)
NEXT: whole-feature F4 review (BASE 5a54d11..HEAD) → one fix wave (README/ROADMAP + findings) → final-gates.sh f4 → publish-evidence.sh f4 f4-modules
Final review: README Important + minors; ONE fix wave b0cce71; scoped re-review: all addressed
Final gates at b0cce71: java 379+2, native 529+2 (0 skipped), requires-clang OK, receipts returncode 0 / oom_kill_delta 0
