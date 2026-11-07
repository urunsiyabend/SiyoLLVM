# SDD ledger — plan: docs/superpowers/plans/2026-11-07-f3c-maps.md
Spec: docs/superpowers/specs/2026-11-07-native-toolchain-design.md

## Preflight scan
| Pair / task | Produces vs consumes | Finding |
|---|---|---|
| T1 self | map methods bind as BoundJavaMethodCallExpression; F3b fix routes SiyoStruct/Object targets to a struct message — T1 must handle SiyoMap targets before that | consistent (noted for implementer) |
| F3a→T1 | for-in uses $mapKeys array + index loop | consistent |
| T2 self | consistent |
Task 1: implementer DONE_WITH_CONCERNS at e8b52be (local, unpublished, NOT yet reviewed)
Ruling: map equality/hash by identity — pinned SiyoMap.java overrides neither equals nor hashCode (oracle-confirmed) — cost if wrong: none vs JVM
Ruling: len(null map) = 0 via non-raising siyo_rt_map_len — oracle behaviour — cost if wrong: none vs JVM
Ruling: m.get("k") vs m[k] unbox NPE messages name different overloads, both reproduced exactly — oracle — cost if wrong: none
NEXT: task review of 2e?..e8b52be (BASE cdc71aa), then final review, fix wave, final-gates.sh f3c, evidence, push, CI
Task 1: review approved (oracle re-run of 7 corpus cases byte-identical; probe matched except documented null-receiver NPE)
Task 1: minor (→final wave): SUBSET null-receiver row should mention deterministic JVM message for nested map reads
Task 1: minor (→final wave): IrEmitter MAP_KEYS/LEN-on-map shared branch; validator test for rejected getLong
Task 1: complete (commits cdc71aa..e8b52be, review clean)
NEXT: whole-feature F3c review (BASE 3c2c2c7 or 0b04773..HEAD) → one fix wave (incl. minors above + README/ROADMAP) → final-gates.sh f3c → evidence → push → CI
Final review: no Critical/Important; README maps + optional minors; ONE fix wave 2018ca3; scoped re-review: all addressed
Final gates at 2018ca3: java 358+2, native 492+2, requires-clang OK, receipts returncode 0 / oom_kill_delta 0
