# F1 evidence — C runtime and strings

Gates were run on the exact tree at commit `8d9af6085e9be077833334e0aeeb91c6f4f031de`,
which is the last code commit of F1. The commit that adds this directory changes
documentation only. Every run went through the host's memory-bounded Maven
wrapper: 768 MiB, no swap, 600 s.

| Gate | Command | Exit | Tests (failures/errors/skipped) | Bounded receipt |
|---|---|---|---|---|
| Java only | `mvn -B clean verify` | 0 | surefire 137 (0/0/0), failsafe 2 (0/0/0) | accepted, returncode 0, oom_kill_delta 0 |
| Native | `mvn -B clean verify -Pnative` with `SIYOLLVM_CLANG` = clang 19.1.7 | 0 | surefire 206 (0/0/0), failsafe 2 (0/0/0) | accepted, returncode 0, oom_kill_delta 0 |
| Gate requires clang | `scripts/check-native-gate-requires-clang.sh` | 0 | — | — |

The files in this directory:

- `java-gate.txt`, `native-gate.txt`: the Maven `Tests run`, `BUILD` and `BOUNDED` lines.
- `requires-clang.txt`: the script's output.
- `reports-summary.json`: the Surefire and Failsafe XML reports parsed per gate and per suite.
- `clang-version.txt`: the clang version used.

Review:

- Each task got a spec review and a quality review, recorded in the SDD ledger.
- A final whole-feature review on F1 found one Important issue, a silent case-mapping fallback. Commit 8d9af60 fixed it, and a scoped re-review checked the fix and found all findings addressed.

`concat_loop` had a peak RSS of 7.8 MB, measured with `/usr/bin/time -v` through the bounded helper. Before 8ece096 it had about 3.6 GB.

The remote SHA and the CI run at the pushed head are recorded in `docs/ROADMAP.md`.
