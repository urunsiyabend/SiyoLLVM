# R1 final gates

These gates ran on the exact tree at commit `e231e8e263f4587fa86288f70fe0061bd9e8a87a`, the last commit of R1. That commit holds the code, tests
and evidence. The commit that adds this directory changes documentation only. Every run went through the
host's memory-bounded Maven wrapper (768 MiB, no swap, 600 s).

| Gate | Command | Exit | Tests (failures/errors/skipped) | Bounded receipt |
|---|---|---|---|---|
| Java only | `mvn -B clean verify` | 0 | surefire 381 (0/0/0), failsafe 2 (0/0/0) | returncode 0, oom_kill_delta 0 |
| Native | `mvn -B clean verify -Pnative` (`SIYOLLVM_CLANG`: clang 19.1.7) | 0 | surefire 734 (0/0/0) in 28 suites, failsafe 2 (0/0/0) | returncode 0, oom_kill_delta 0 |
| Gate requires clang | `scripts/check-native-gate-requires-clang.sh` | 0 | — | — |

The R1 suites inside the native gate:

| Suite | Tests | Time |
|---|---|---|
| GcStressNativeTest | 128 | 60.3 s |
| MemoryBoundsNativeTest | 8 | 5.6 s |
| RuntimeGcNativeTest | 8 | 6.2 s |
| LedgerAppNativeTest | 27 | — |
| LedgerOracleNativeTest | 27 | — |
| NativeCorpusTest | 86 | — |
| ModulesNativeTest | 15 | — |

The rest of the R1 evidence is in sibling directories:

| Directory | Contents |
|---|---|
| `../red/` | the RED runs, before the collector |
| `../collector/` | the collector tests, including the RED-first runs |
| `../ledger/` | the ledger measured at the final runtime, with an earlier measurement and the historical baseline |

`reports-summary.json` is a programmatic aggregate of the Surefire and Failsafe XML for each gate.
