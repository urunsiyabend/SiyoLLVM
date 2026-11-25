# R2 — fresh remote clone gates

`git clone --branch development/native-toolchain https://github.com/urunsiyabend/SiyoLLVM.git` was run into
an empty scratch directory with no sibling SiyoCompiler checkout. The clone is at `60b785f063b91e423bb5f9da11a73577274ee845`,
the pushed R2 head. Both gates ran inside the clone through the host's bounded Maven wrapper.

| Gate | Exit | Surefire | Failsafe | Bounded receipt |
|---|---|---|---|---|
| `mvn -B verify` | 0 | 393 (0 failures, 0 errors, 0 skipped) | 2 (0/0/0) | returncode 0, oom_kill_delta 0 |
| `mvn -B verify -Pnative` | 0 | 780 (0 failures, 0 errors, 0 skipped) | 2 (0/0/0) | returncode 0, oom_kill_delta 0 |

Exact-head CI at `60b785f` is run 45573214389. Both of its jobs passed: "Java tests (no LLVM)" and "Native gate
(clang 19)". Its log shows these suites, each with 0 skipped (`../final-gates/ci-45573214389-test-lines.txt`):

| Suite | Tests |
|---|---|
| GcStressNativeTest | 145 |
| NativeCorpusTest | 101 |
| ModulesNativeTest | 17 |
| Ledger suites | 27 each |
