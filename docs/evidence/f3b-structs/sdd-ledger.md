# SDD ledger — plan: docs/superpowers/plans/2026-11-07-f3b-structs.md
Spec: docs/superpowers/specs/2026-11-07-native-toolchain-design.md

## Preflight scan
| Pair / task | Produces vs consumes | Finding |
|---|---|---|
| T1→T2 | SiyoStruct + struct_get/set; methods take self: SiyoStruct | consistent |
| T1 self | Frontend un-rejects StructDeclaration only; impl/enum in T2 | consistent |
| T2 self | impl bodies may already be lowered by binder — plan flags double-lowering check | consistent |
| F2/F3a→T1 | coerce boxing; arrays of structs (elements are ptr, tag 7) | consistent |
| T3 self | consistent |
Task 1: implementer DONE_WITH_CONCERNS at ff55202 (extra: cast_struct_arg LinkedHashMap message, unbox_*_field exact NPE; typeName added)
Ruling: extra runtime functions for exact reference messages accepted — oracle-measured differences — cost if wrong: small API growth
Task 1: minor (carried into Task 2): corpus line for field assignment used as a value (println(p.x = 5))
Task 1: minor (carried into Task 2): SUBSET must state whether ==/!= between struct-typed values is accepted/rejected
Task 1: minor (carried into Task 2): validator test pinning "member access on values of type any is"
Task 1: minor (deferred): FQN ArrayList in IrEmitter; zero-field literal untested (parser may reject)
Task 1: complete (commits 091d90c..ff55202, review clean)
Task 2: implementer DONE at 34f0ac4
Task 2: minor (→final wave): no test for a nested function inside an impl-method body
Task 2: minor (deferred): putIfAbsent hides duplicate method decl (binder rejects first); name-keyed implMethods bookkeeping
Task 2: complete (commits ff55202..34f0ac4, review clean)
Final review: 2 Important (misleading "Java interop" message + SUBSET impl wording; cross-feature corpus coverage) + README; ONE fix wave 6c609fa; scoped re-review: all addressed (oracle re-run byte-identical)
Final wave minor (deferred): struct-receiver message also applies to `any` receivers ("struct or any" would be more precise)
Final gates at 6c609fa: java 335+2, native 451+2, requires-clang OK, receipts returncode 0 / oom_kill_delta 0
