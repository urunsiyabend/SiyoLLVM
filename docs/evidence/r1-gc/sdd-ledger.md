# SDD ledger — plan: docs/superpowers/plans/2026-11-25-r1-gc.md
Spec: docs/superpowers/specs/2026-11-25-memory-reclamation-design.md; goal docs/RUNTIME_READINESS_GOAL.md

## Preflight scan
| Pair / task | Produces vs consumes | Finding |
|---|---|---|
| T1→T2 | SIYO_RT_STATS line + MemoryBoundsNativeTest bounds consumed unchanged by T2 | consistent; T2 must not loosen bounds |
| T1 self | RED must fail on bounds with real numbers (stats commit first) and stay under ~400 MB | consistent |
| T2 self | stress suite reruns all corpus/module/ledger cases | consistent |
| T3 self | ledger re-measure vs historical baseline kept distinct | consistent |
Ruling: GC architecture = non-moving mark-sweep with conservative roots (spec) — routine decision under renewed mandate; alternatives (RC, statepoints) rejected in spec — cost if wrong: false retention/collector bugs, caught by stress corpus
Task 1: review approved; Important: bounds can't detect partial leaks <~50 MiB (exception payloads, self-cycles)
Ruling: TIGHTEN (never loosen) before Task 2: add flatness assertion rss(N3) <= rss(N2) + 8 MiB to every workload; add a heap-pointer module global (string/map) in gc_modules; re-run RED, keep the original RED log alongside as superseded-by-tightening — a gate that passes leaky collectors is not a gate — cost if wrong: possible flakiness, mitigated by 8 MiB slack
Task 1: fix round 1/5 (tightening addressed; RED reconfirmed: 3 failures per workload, stdout = JVM at all 24 runs; scope peak 499 MiB) commit 8f0b8f5
Task 1: complete (commits 724bbb9..8f0b8f5, review clean after fix round) — NOT pushed (native gate intentionally red until Task 2)
Task 1: note for Task 2: RuntimeStatsNativeTest hard-codes collections==0 and SUBSET says "collections is 0" — Task 2 must update both; heap_peak_kb must include array/map backing stores
Task 2: implementer DONE at 77f5281: MemoryBounds GREEN (5.9–7.5 MiB at all N vs 138–294 MB RED), GcStress 128/128 byte-identical, RuntimeGc 7/7; native gate 733+2 in 141 s, scope peak 362 MiB
Task 2: concern: one-past-end pointer of a small block resolves to the next block (possible premature free if only an end pointer survives an allocation) → review named risk
Task 2: review Needs fixes (one-past-end unmitigated) → fix round 1 at 39235b1 (+1 slack; 24 RED→GREEN end-pointer checks; stress escape removed)
Task 2: fix round 1/5 (4 addressed, 0 open; commits 77f5281..39235b1)
Task 2: minor (→Task 3): add a 24-byte size class (16-byte boxes now take 32 with the +1 slack)
Task 2: minor (deferred): NO_ALLOCATION name list brittle; SLOW_CASES stress n measured pre-slack
Task 2: complete (commits 333165a..39235b1, review clean after fix round)
Task 3: c66bc5b (24-byte class, RED 3 checks → GREEN), 68e3a56 (ledger evidence: x1/x3/x10 rss 7.3/9.4/19.9 MiB vs historical 27.7/79.7/262 MiB; oracle match all), cec2571 (docs); gates java 381+2, native 733+2
NEXT: whole-feature review → fix wave → final gates → fresh clone → publish → CI
Final review: Important mapping-per-large-block regression + stale ROADMAP lines; ONE fix wave 363d950 (RED) + 6a889ec (medium classes 2560..32768, 1 MiB page batches; maps 20463→92)
Ledger re-measured at final runtime 6a889ec → e231e8e (x10 rss 20 376 KiB, oracle match all)
Final gates at e231e8e263f4587fa86288f70fe0061bd9e8a87a: java 381+2, native 734+2 (GcStress 128, MemoryBounds 8, RuntimeGc 8, ledger 27+27; 0 skipped), requires-clang OK, receipts rc 0 / oom 0
Published: f61099c (remote verified), CI run 45564871470 success (Java + Native; R1 suites ran, 0 skipped); fresh remote clone gates green (381+2 / 734+2)
