# SDD ledger — plan: docs/superpowers/plans/2026-11-07-f5-ledger.md
Spec: projects/ledger/FORMAT.md (binding), docs/superpowers/specs/2026-11-07-native-toolchain-design.md

## Preflight scan
| Pair / task | Produces vs consumes | Finding |
|---|---|---|
| T1→T2 | committed tests/<case>/expected.* consumed by the Python oracle test | consistent; T2 must not read src/ or expected.* before writing the oracle |
| T1 self | fixtures incl. unwritable report path: must be deterministic across CI (non-root) | noted |
| T3 self | fresh clone gate in scratch, bounded | consistent |
Ruling: FORMAT.md supersedes the design spec's amount_cents sketch (decimal amounts in the CSV, cents internally) — more realistic input — cost if wrong: none
Task 1: implementer DONE at 134bd0d (16 cases; large 5577 rows peak RSS ~28 MB, 0.04 s)
Task 1: concern → Task 3 docs: struct read from a map / cross-module nested struct field reads are untyped (any); workaround via struct-typed parameter
Task 1: concern → Task 3 docs: a module compile failure surfaces in importers as "Name/Type does not exist"
Task 1: review approved (reviewer's scratch Python reading agreed 12/12 non-I/O; JVM oracle + generator reproduce byte-identical)
Task 1: minor (→final wave): fixtures for trim edges (U+0001 trimmed, U+00A0/U+3000 not), header variants (trailing space, blank line first, only "\n"), usage with 0 args, money -0.42, category-net tie across surrogate boundary; clearer memo in line_endings; unique work dir in LedgerAppNativeTest
Task 1: complete (commits ca61ac7..134bd0d, review clean)
Task 2: implementer DONE at c6ceebe (Python oracle agreement 16/16 first run; independence statement in report)
Task 2: review approved (structurally independent; 9 adversarial cases app vs oracle: 0 disagreements; scratch f5t2-review)
Task 2: minor (→Task 3): commit fixtures for trim boundary, -0.42, \r\r\n, I/O on directory paths; stderr byte compare; redundant len check
Task 2: complete (commits 134bd0d..c6ceebe, review clean)
Task 3 (impl part): b00281e fixtures/harness, d71a960 docs; 27 cases app 27/27 oracle 27/27; large native 28 MB/0.04 s vs JVM 67 MB/0.18 s; x10 (3.1 MB) native 268 MB vs JVM 101 MB (never-free ~87 B RSS per input byte)
Ruling: never-free memory growth (~87 B/input byte) is documented as a capacity limit, not fixed in the milestone — GC/arena is owner-dependent post-milestone work — cost if wrong: inputs beyond ~8 MB exceed a 768 MiB budget
NEXT: whole-feature F5 review → one fix wave → final-gates f5 → publish → fresh clone gates of pushed head → milestone status
Final review: 3 Important doc items (platform claims, private evidence paths, stale note) + minors; ONE fix wave 9f0ecf3; scoped re-review: all addressed
Final gates at 9f0ecf3: java 379+2, native 583+2 (ledger app 27/27, oracle 27/27, 0 skipped), requires-clang OK, receipts returncode 0 / oom_kill_delta 0
