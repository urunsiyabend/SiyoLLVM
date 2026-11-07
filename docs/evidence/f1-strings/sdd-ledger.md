# SDD ledger — plan: docs/superpowers/plans/2026-11-07-f1-runtime-strings.md
Spec: docs/superpowers/specs/2026-11-07-native-toolchain-design.md

## Preflight scan
| Pair / task | Produces vs consumes | Finding |
|---|---|---|
| T1→T2 | RuntimeSource.writeTo(Path)->Path | consistent |
| T1→T3,T4 | siyo_rt.h SiyoStr layout, siyo_rt_raise_java, siyo_rt_alloc | consistent |
| T3↔T4 | concat_loop needs len/substring (T4) | conflict; plan itself allows moving it |
| T1 self | tests vs code | consistent |
| T2 self | consistent |
| T3 self | toString(string) via siyo_rt_str_of_string | consistent |
| T4 self | split explicitly deferred to F3 | consistent |
| T5 self | evidence after review/push | consistent |

Ruling: concat_loop corpus case is written in Task 4, not Task 3 — it needs len/substring from Task 4 — cost if wrong: none (only placement).
Ruling: execution by subagents on sonnet for implementers (multi-file integration), opus for reviewers of runtime-heavy tasks — cost if wrong: extra fix rounds.
Task 1: minor (deferred): no test for runtime temp-dir cleanup after clang timeout
Task 1: minor (deferred): Clang.deleteTree swallows cleanup failures silently
Task 1: minor (deferred): .siyo-rt dir left if JVM killed mid-compile (document in README)
Task 1: minor (deferred): siyo_rt_raise_java/siyo_rt_alloc untested until first use
Task 1: complete (commits b36b65e..90f3129, review clean)
Task 2: minor (deferred): emit-runtime multi-extra-arg message doesn't name the argument; lone '-' rejected unlike other parser
Task 2: minor (deferred): README manual-link line omits -fverify-intermediate-code -w
Task 2: complete (commits 90f3129..687846d, review clean)
Task 3: implementer DONE at d9a90ac with concern: never-free + concat => quadratic memory for += loops
Ruling: SiyoStr becomes {tag, utf16_len, len, data ptr, buf ptr} with an appendable shared buffer (append in place when a ends at buf's used mark) — never-free makes quadratic concat unusable for the target app; GC stays post-milestone — cost if wrong: literal-layout churn, a few hundred lines of C
Task 3: fix round 1/5 interrupted (SIGTERM, session 1) after RED tests only; preserved in WIP commit (see git log), concat_loop expectation re-verified against oracle (cmp match)
Task 3: fix round 1/5 implemented in session 3 (fresh implementer, bounded wrapper): 8ece096; concat_loop RSS 7.8 MB
Task 3: review ⚠️ concat_loop oracle provenance — resolved: controller regenerated with scripts/reference-oracle.sh in session 2 and cmp matched
Task 3: minor (fixed by controller, docs only): stale spec string bullet
Task 3: minor (carried into Task 4): IrEmitter.text() should throw unsupported for non-String ptr types
Task 3: minor (deferred): siyo_rt_str_from_bytes duplicate INT32_MAX check
Task 3: complete (commits 687846d..8ece096, review clean)
Task 4: implementer DONE_WITH_CONCERNS at 67756fe
Ruling: chr follows the oracle (Character.toString(int) code point, IllegalArgumentException out of range), not the plan's (char) cast — oracle is the binding reference semantics — cost if wrong: none observable vs JVM
Ruling: builtin dispatch keyed by identity (FunctionSymbol.equals ignores scope); equals-keyed user-function maps are a known latent hazard to re-check in F4 (modules) — cost if wrong: same-name/arity functions conflated
Ruling: case-mapping deviations measured against glibc 2.43 documented in SUBSET.md; CI glibc may differ for Unicode 16+ pairs — corpus must avoid them (CI is the check)
Task 4: fix round 1/5 (4 addressed, 0 open; commits 67756fe..30c6e72)
Task 4: minor (deferred): non-ASCII substring O(n) offset scan per call → quadratic loops; note in roadmap
Task 4: minor (deferred): text() refusal test uses reflection on private internals
Task 4: complete (commits b5b048d..30c6e72, review clean after fix round)
Final review: 1 Important (silent case-mapping fallback) + minors; ONE fix wave 8d9af60; scoped re-review: all addressed
Final gates at 8d9af60: java 137+2, native 206+2, requires-clang OK, bounded receipts accepted (oom_kill_delta 0)
Published: 2774fc7 (remote verified), CI run 42328908151 success (Java + Native jobs)
