# R1 — fresh remote clone gates

Procedure: `git clone --branch development/native-toolchain https://github.com/urunsiyabend/SiyoLLVM.git`
into an empty scratch directory with no sibling SiyoCompiler checkout. The clone was at commit
`f61099cdf8f4243148571f510b80fd915a23d484`, the pushed R1 head. Both gates then ran inside the clone through
the host's bounded Maven wrapper.

| Gate | Exit | Surefire | Failsafe | Bounded receipt |
|---|---|---|---|---|
| `mvn -B verify` | 0 | 381 (0 failures, 0 errors, 0 skipped) | 2 (0 failures, 0 errors, 0 skipped) | returncode 0, oom_kill_delta 0 |
| `mvn -B verify -Pnative` | 0 | 734 (0 failures, 0 errors, 0 skipped) | 2 (0 failures, 0 errors, 0 skipped) | returncode 0, oom_kill_delta 0 |

Exact-head CI: run 45564871470 at `f61099c`. Both jobs passed, "Java tests (no LLVM)" and "Native gate
(clang 19)". The CI log shows GcStressNativeTest 128, MemoryBoundsNativeTest 8, RuntimeGcNativeTest 8, and
both ledger suites at 27 each, all with 0 skipped (`../final-gates/ci-45564871470-test-lines.txt`).
