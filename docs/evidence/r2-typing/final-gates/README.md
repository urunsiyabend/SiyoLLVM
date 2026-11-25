# R2 final gates

These gates ran on the exact tree at commit `c33bbb2213b4171b2ef95bb78f79f9d4923ccfc2`, the last commit of R2. It holds the code, tests, evidence
and the ROADMAP range. The commit that adds this directory changes documentation only. Every run went
through the host's memory-bounded Maven wrapper (768 MiB, no swap, 600 s).

| Gate | Command | Exit | Tests (failures/errors/skipped) | Bounded receipt |
|---|---|---|---|---|
| Java only | `mvn -B clean verify` | 0 | surefire 393 (0/0/0), failsafe 2 (0/0/0) | returncode 0, oom_kill_delta 0 |
| Native | `mvn -B clean verify -Pnative` (`SIYOLLVM_CLANG`: clang 19.1.7) | 0 | surefire 780 (0/0/0) in 28 suites, failsafe 2 (0/0/0) | returncode 0, oom_kill_delta 0 |
| Gate requires clang | `scripts/check-native-gate-requires-clang.sh` | 0 | — | — |

The native gate includes these suites:

| Suite | Tests | Time |
|---|---|---|
| NativeCorpusTest | 101 | 43.5 s |
| ModulesNativeTest | 17 | 7.4 s |
| GcStressNativeTest | 145 | 68.4 s |
| MemoryBoundsNativeTest | 8 | 5.6 s |
| RuntimeGcNativeTest | 8 | 6.3 s |
| LedgerAppNativeTest | 27 | 0.7 s |
| LedgerOracleNativeTest | 27 | 0.7 s |

`reports-summary.json` is a programmatic aggregate of each gate's Surefire and Failsafe XML.

The remaining R2 evidence is in sibling directories:

| Directory | Contents |
|---|---|
| `../probe-matrix.md` | the reference's behaviour for each erased use site |
| `../red/` | the RED cases, failing before the fix |
| `../probes/` | the probe programs |
| `../ledger/` | ledger memory at ×1 and ×10 after the workaround removal |
