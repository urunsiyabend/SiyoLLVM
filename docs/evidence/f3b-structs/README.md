# F3b evidence — structs, `impl` methods, enums

Gates ran on the exact tree at commit `6c609fa5884cec00f0d33fa95bf42187b76a25a7`, the last code commit of
F3b. The commit that adds this directory changes documentation only. Every run went through the host's
memory-bounded Maven wrapper (768 MiB, no swap, 600 s).

| Gate | Command | Exit | Tests (failures/errors/skipped) | Bounded receipt |
|---|---|---|---|---|
| Java only | `mvn -B clean verify` | 0 | surefire 335 (0/0/0), failsafe 2 (0/0/0) | returncode 0, oom_kill_delta 0 |
| Native | `mvn -B clean verify -Pnative` (`SIYOLLVM_CLANG`: clang 19.1.7) | 0 | surefire 451 (0/0/0), failsafe 2 (0/0/0) | returncode 0, oom_kill_delta 0 |
| Gate requires clang | `scripts/check-native-gate-requires-clang.sh` | 0 | — | — |

`reports-summary.json` holds the XML reports, parsed per gate and per suite. `sdd-ledger.md` records the
task reviews, rulings, deferred minors and the final review. Every new corpus expectation was generated
by `scripts/reference-oracle.sh`; reviewers re-ran the oracle on scratch copies and got byte-identical
output.
