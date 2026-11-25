# SDD ledger — plan: docs/superpowers/plans/2026-11-07-f2-exceptions.md
Spec: docs/superpowers/specs/2026-11-07-native-toolchain-design.md (§2, §4)

## Preflight scan
| Pair / task | Produces vs consumes | Finding |
|---|---|---|
| T1→T2 | ptr `any` values, siyo_rt_box_*, siyo_rt_any_to_string used for thrown payloads and catch var | consistent |
| T1→T2 | T1 unbox/cast raise CCE/NPE via siyo_rt_raise_java, which is fatal until T2 | consistent: T1 corpus any_cast_error is uncaught (same output fatal or propagated) |
| T2→T3 | handler mechanism consumed by try expressions | consistent |
| F1 state→T2 | F1 raising fns (substring, parseLong, ord, chr, NPE paths) return dummy after raise; siyo_rt_fail (case-mapping locale) is NOT a Java exception and stays fatal | plan silent on siyo_rt_fail |
| T1 self | null literal accepted | consistent |
| T2 self | corpus list vs Review Focus tests | consistent |
| T3 self | consistent |
| T4 self | docs/evidence/push | consistent |

Ruling: siyo_rt_fail paths (missing C.UTF-8 for case mapping, OOM, thread start) remain fatal, uncatchable — they are environment failures, not Java exceptions the reference would raise — cost if wrong: none vs JVM (JVM has no such failure)
Ruling: all execution bounded: plain mvn wrapper; oracle/clang/binaries via bounded.py — host rule
Task 1: implementer DONE_WITH_CONCERNS at de865b5
Ruling: any boxing/casting happens at the binder's implicit coercion points (decl, assign, arg, return) since no BoundConversionExpression exists for any — matches binder reality — cost if wrong: missed coercion sites surface as emitter IllegalStateException (loud)
Ruling: forms where the reference crashes with VerifyError (primitive assigned to any after declaration; primitive returned from fn -> any) are rejected at compile time — no defined reference behaviour to match — cost if wrong: valid-looking programs rejected (documented)
Ruling: NPE unboxing message lacks the JVM's helpful "because ..." suffix for int/bool; documented deviation — no call-site info in runtime — cost if wrong: stderr text differs in that case only
Task 1: minor (carried into Task 2): literal-null unbox to int/bool could print exact JVM message `... because "null" is null`
Task 1: minor (carried into Task 2): corpus line for any → non-any user parameter
Task 1: minor (deferred): raise_format("%s", const) roundabout
Task 1: complete (commits 5ddf07c..de865b5, review clean)
Task 2: implementer DONE_WITH_CONCERNS at 82ec364
Ruling: uncaught exceptions reported only by @siyo_program after init_globals/init/main; every function (incl. init/main) returns default with pending set — same output, and correct when user code calls main() inside try — cost if wrong: none observed
Ruling: pending flag is a thread_local i8 read directly from IR — single program thread today; thread_local keeps future spawn safe — cost if wrong: minor TLS access cost
Task 2: minor (carried into Task 3): corpus case for uncaught rethrow of a caught Java exception
Task 2: minor (carried into Task 3): unit test tying RAISING to runtime builtins (raises vs never-raises lists)
Task 2: minor (deferred): hard-coded tag ints in IrEmitter literal-null path; FQN ArrayList in entryPoint
Task 2: complete (commits de865b5..82ec364, review clean)
Task 3: implementer DONE at 3366c7d
Ruling: try expressions accepted only in positions with an empty JVM operand stack (statement, decl/assign value, condition, return/throw value, first arg of user fn/toString); others rejected because the reference fails with VerifyError (three safe positions also rejected for simplicity) — cost if wrong: some valid programs rejected (documented, loosening is safe later)
Task 3: minor (deferred→final wave): RuntimeIrTest body-end parse only exact "}" — harden
Task 3: minor (deferred→final wave): validator `return` without value leaves _emptyStack true
Task 3: minor (deferred→final wave): corpus coverage for join with no predecessors, return f(try…), f(y = try…)
Task 3: minor (deferred→final wave): SUBSET try-expression rejected-position sentence hard to parse
Task 3: complete (commits 82ec364..3366c7d, review clean)
Final review: 4 Important (untrue SUBSET VerifyError claim, payload-text corpus gap, try-expr CFG corpus gaps, stale README) + minors; ONE fix wave 0c1c449; scoped re-review: all addressed
Final gates at 0c1c449: java 237+2, native 325+2, requires-clang OK, bounded receipts returncode 0 / oom_kill_delta 0
Published: 4f934b2 (remote verified), CI run 42333036738 success (Java + Native jobs)
