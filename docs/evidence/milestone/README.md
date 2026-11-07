# Useful-native-toolchain milestone — evidence

This directory checks each criterion in `docs/DEVELOPMENT_GOAL.md` ("Milestone completion") against the
pushed head `c317efbdf7a062265f86f46cb3167f5e5e5fed53` on `development/native-toolchain`.

| Criterion | Evidence |
|---|---|
| A substantial application is implemented | `projects/ledger`: a five-module Siyo command-line program (about 520 lines) that reads a CSV, validates rows, aggregates per account and category, sorts, formats and writes a report, and handles all error paths. Behaviour contract: `projects/ledger/FORMAT.md`. |
| The promised language features have real native regression coverage | F1–F4 evidence directories. Native gate: 583 tests across 23 suites, with corpus, module and std cases compared byte for byte against the JVM reference. |
| Data and error-path oracles are independently checked | 27 ledger cases. Expected outputs come from the JVM reference running the same sources (`projects/ledger/tools/oracle-jvm.sh`). An independent Python oracle written only from FORMAT.md (`projects/ledger/oracle/`) agrees 27/27 (`LedgerOracleNativeTest`). The native app also agrees 27/27 (`LedgerAppNativeTest`). See `docs/evidence/f5-ledger/`. |
| A fresh clone passes the complete gates | `git clone` of `c317efb` from origin into an empty scratch directory with no sibling SiyoCompiler checkout. `mvn -B verify`: 379 tests + 2 IT, 0 failures/errors/skips, exit 0. `mvn -B verify -Pnative`: 583 tests + 2 IT, 0 failures/errors/skips, exit 0. Both ran under the host's 768 MiB bounded wrapper; receipts show returncode 0 and oom_kill_delta 0. Files: `fresh-clone-*-gate.txt`. |
| Exact-head CI is green | CI run 42346474485 at `c317efb`: "Java tests (no LLVM)" passed and "Native gate (clang 19)" passed. In CI, both ledger suites ran 27 tests with 0 skipped (`ci-42346474485-test-lines.txt`). |
| Remaining limitations are documented | `docs/ROADMAP.md`, "Milestone status and remaining limitations", and `docs/SUBSET.md`. Measured never-free memory growth: `docs/evidence/f5-ledger/resources/`. |

This is a useful-native-toolchain milestone. It does not claim complete Siyo compatibility or
production readiness: see the limitations section.
