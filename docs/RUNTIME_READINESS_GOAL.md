# Runtime readiness goal

## Mandate (owner renewal, 2026-11-25)

The owner renewed the development mandate on 2026-11-25. It covers further practical toolchain work on
`development/native-toolchain`. Each completed feature is to be tested, reviewed, committed and pushed
without force. Merging to main, tagging and releasing are not authorised. The useful-native-toolchain
milestone (`docs/DEVELOPMENT_GOAL.md`, evidence in `docs/evidence/milestone/`) stays as it is.
PR #1 stays open and unmerged.

The goal is practical runtime readiness. It is not production readiness, and it is not full Siyo
compatibility.

## Outcome

1. **Memory reclamation.** Realistic long-running data-processing loops must not allocate without bound.
   Memory that becomes unreachable is reclaimed, and the existing reference semantics are kept: aliasing,
   cycles, exceptions and error paths, modules, and container behaviour all stay as they are. The
   reclamation design is a routine technical decision under this mandate. It is not an owner decision.
2. **Typing gaps.** Fix typing gaps that a real application exposes, where they get in the way:
   - array elements returned across modules are typed `any`;
   - struct values read through a map field local are typed `any`.
3. **Documentation.** Keep explicit "not supported" diagnostics. Document honestly the remaining
   compatibility, capacity and scalability limits, including:
   - per-call UTF-16 index scans on non-ASCII strings;
   - hand-written sorting over distinct keys.

The work proceeds in coherent vertical slices. It does not implement every unsupported language
feature at once.

## Acceptance for each feature

A feature is complete only when every item below is met.

- **RED first.** Regression tests are written first and fail before the fix. They then go through
  review and fixes.
- **Memory evidence.** Memory use is shown to stay bounded and is quantified at increasing dataset and
  loop sizes. Correctness at those sizes is checked independently, with reference or oracle output kept
  separate from native results. Historical measurements are kept separate from new ones.
- **Regression coverage.** Tests cover aliasing, cycles, lifetime, error paths and cross-module use,
  where the feature touches them.
- **Gates.** The complete Java gate and native gate pass on the final tree. Their Surefire and Failsafe
  XML reports are preserved separately and aggregated by a program.
- **Clean clone and CI.** A fresh clone of the remote passes, and the exact pushed head is green in CI.
- **Commits.** Author and committer are `urunsiyabend@gmail.com`.
- **No shortcuts.** Resource and correctness tests are never weakened to show progress, and a memory
  improvement is never claimed by changing semantics.

## Execution constraints

These carry over from the milestone mandate.

- Maven and native test processes run inside the host's memory-bounded wrapper, and manual probes run
  through the bounded helper.
- Child implementation and review agents run in the foreground and are waited for.
- At each bounded checkpoint, record the exact remaining step and where its evidence is.
