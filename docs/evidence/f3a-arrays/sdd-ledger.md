# SDD ledger — plan: docs/superpowers/plans/2026-11-07-f3a-arrays.md
Spec: docs/superpowers/specs/2026-11-07-native-toolchain-design.md

## Preflight scan
| Pair / task | Produces vs consumes | Finding |
|---|---|---|
| T1→T2 | SiyoArray layout, siyo_rt_array_*; T2 for-in uses len/index from T1 | consistent |
| T1 self | builtins list vs corpus | consistent; `removeAt` has no value (probed), `pop` returns element |
| T2 self | split edge printing ambiguity ([""] vs []) resolved by len checks | consistent |
| F2→T1 | checked casts/boxing in coerce; RAISING classification enforced by RuntimeIrTest | consistent |
| T3 self | consistent |
Task 1: implementer DONE_WITH_CONCERNS at 1d0e5ae
Ruling: siyo_rt_array_set returns the replaced element (brief said void) — the reference narrows the old element to the new value's type (CCE observable) — cost if wrong: none vs JVM
Ruling: writes/push/pop/removeAt on an array held in `any` rejected (reference raises IncompatibleClassChangeError) — no defined reference semantics — cost if wrong: such programs rejected (documented)
Ruling: null element into int/bool NPE message shorter than JVM's (documented deviation; could be exact later)
Task 1: review spec ❌ Extra: for-in over arrays accepted without tests in Task 1
Ruling: keep for-in acceptance; Task 2 (next, same feature, unpublished in between) adds its validator test and corpus coverage — reverting then re-adding is churn — cost if wrong: none once Task 2 lands
Task 1: minor (carried into Task 2): skip pending checks after pushes inside array literals (cannot raise)
Task 1: minor (deferred): comment on __builtin_frame_address in possibly-inlined siyo_rt_check_stack
Task 1: complete (commits 3de4ac3..1d0e5ae, review approved; Extra ruled)
Task 2: implementer DONE_WITH_CONCERNS at c581098 (for-in over strings accepted: reference supports it, UTF-16 units)
Task 2: review (re-dispatched after run-3 timeout lost the first) approved; ⚠️ channel for-in rejection untested
Task 2: minor (→final wave): SUBSET: for-in-over-string variable is `any` (len/ord rejected)
Task 2: minor (→final wave): IrEmitterTest forInReadsTheLengthOnEveryIteration doesn't test its name
Task 2: minor (→final wave): validator test row for channel for-in rejection
Task 2: complete (commits 1d0e5ae..c581098, review clean)
Final review: no code defects; 2 must-fix doc truth items (README summary, SUBSET string for-in var is any) + minors; ONE fix wave 2e5c8dd; scoped re-review: all addressed
Final gates at 2e5c8dd: java 299+2, native 404+2, requires-clang OK, receipts returncode 0 / oom_kill_delta 0
