# Native toolchain development goal

## User mandate

The owner authorized sustained Claude Code development on 2026-11-07 (local time): bring this standalone LLVM project to a genuinely useful level, continue working without per-feature approval, and commit/push each completed feature. This is authority to develop and publish scoped feature commits, not to release/tag, rewrite history, alter subscriptions, or change unrelated projects.

## Outcome

Turn the current scalar-only experimental compiler into a dependable native Siyo toolchain that can build and run a nontrivial multi-module command-line application doing real data processing and file I/O. Work toward practical language/runtime coverage and usability, not a growing collection of tiny demos or documentation-only milestones. Preserve working existing programs and explicitly state remaining limitations.

Use Superpowers to own design, roadmap, feature ordering, decomposition, implementation and independent code review. Make routine technical decisions yourself. Start from the actual source and known limitations; choose coherent vertical slices rather than implementing disconnected surface syntax. Likely enabling areas include modules, useful string operations, floating-point numbers, data structures/user-defined types, safe runtime ownership, file I/O and error handling; determine their ordering and exact design from the application goal. Do not promise support until end-to-end tests demonstrate it.

Choose and build one substantial application under `projects/` that exercises multiple modules, structured/collection data, strings, input/output and error paths. Give it realistic fixtures, deterministic expected results and independent oracles. The toolchain must build it from a fresh clone without a sibling SiyoCompiler checkout or a JVM fallback in generated programs.

## Delivery loop

- Maintain `docs/ROADMAP.md` as an evidence-backed roadmap: concrete acceptance criteria, dependency ordering, status, feature commit references and genuine blockers. Keep continuity notes concise.
- Develop only in this SiyoLLVM worktree on `development/native-toolchain`. Keep the original SiyoCompiler repositories and the stable SiyoLLVM main checkout untouched. Update frontend snapshots only through the documented provenance-preserving process; isolate adaptations instead of silently editing pinned upstream files.
- Complete coherent features with tests, docs and review. Commit and non-force push to `origin development/native-toolchain` as each is complete; do not wait for the entire mission to publish useful work. Do not merge to main, tag or create a release under this mandate.
- Before each feature push, run the complete Java and mandatory native gates on the final tree. Fix review blockers, rerun after fixes and record real output under a feature-specific evidence path. Native tests must fail rather than skip when the toolchain is absent. Preserve genuine historical RED evidence; never manufacture it after the fact.
- Verify the remote SHA and exact-head CI after each push. Keep an unmerged PR from `development/native-toolchain` to `main` open so the existing pull-request CI runs for every pushed feature (or make an equivalent scoped CI trigger change). Repair failures before starting an unrelated feature. Author and committer email must be `urunsiyabend@gmail.com`.
- Keep regression coverage for Unicode names/paths, UTF-8 under `LC_ALL=C`, embedded-NUL strings, integer edge cases, short-circuit CFG validity, runtime errors and destructive output aliases. Newly allocated/container/module features need lifetime, aliasing, bounds, initialization-order and cross-module tests as appropriate.
- Do not remove safety checks or weaken tests to manufacture progress. No silent fallback, semantic substitution, misleading benchmarks, synthetic output or unsupported claims. Document measured compatibility deviations.
- Keep child implementation/review agents foreground/waited within bounded print runs. Before a run ends, save the actual remaining step and evidence paths. Continue the next dependency-ready feature after completing one; do not stop merely because a plan was written or one feature landed.
- Escalate only genuinely owner-dependent decisions, credential/quota problems and substantive blockers. Do not modify automation, launch competing workers or bypass permissions. Respect a supervisor STOP/pause instruction.

## Milestone completion

The supervisor may mark this goal complete only after the substantial application is implemented, the promised language features have real native regression coverage, a fresh clone passes complete gates, independently checked data/error-path oracles pass, exact-head CI is green, and remaining limitations are documented. This is a useful-native-toolchain milestone, not a claim of complete Siyo compatibility or production readiness.
