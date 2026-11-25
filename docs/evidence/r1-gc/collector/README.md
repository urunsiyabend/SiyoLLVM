# R1 Task 2: collector evidence (2026-11-25)

Pre-collector HEAD 333165a; host clang `$SIYOLLVM_CLANG` (siyo-llvm-19); every run under the bounded
768 MiB scope (`bounded.py`, plain `mvn` wrapper).

## RED: the new tests against the never-free runtime

`mvn -B verify -Pnative -Dtest='RuntimeGcNativeTest,GcStressNativeTest' -Dsurefire.failIfNoSpecifiedTests=false`,
with the tests written and the runtime still at 333165a: `gc-tests-red.log`, receipt
`gc-tests-red-receipt.json` (child returncode 1, oom_kill_delta 0).

- `RuntimeGcNativeTest`: 7 run, 7 failed. The C hooks harness does not compile (no `siyo_rt_alloc_noscan`,
  no `siyo_rt_gc_*`); every Siyo program prints the right output but reports `collections=0` under
  `SIYO_GC_STRESS` (no stress mode, no collector).
- `GcStressNativeTest`: 129 run (128 cases plus the class-level check), 70 failed, all with "no collection
  under stress" (the other 58 allocated under 1 KiB in all); every stdout, stderr, exit and report already
  matched, so the suite's comparisons themselves were sound before the collector.

## GREEN: after the collector

- `mvn -B verify`: exit 0, 381 tests plus 2 IT.
- `mvn -B verify -Pnative`: exit 0, 733 tests plus 2 IT, 141 s wall, scope memory.peak 362 MiB
  (was 511 MiB at Task 1), oom_kill_delta 0 (`native-gate-green-receipt.json`).
- `GcStressNativeTest` alone: 128 cases, 58 s.

### Memory workloads (`MemoryBoundsNativeTest` executables, run by hand with `SIYO_RT_STATS=1`, KiB)

| Workload | N1 / N2 / N3 | collections | heap_peak_kb | rss N1 | rss N2 | rss N3 | never-free rss N3 (Task 1) |
|---|---|---|---|---|---|---|---|
| gc_arrays | 50k / 100k / 200k | 12 / 24 / 49 | 4 609 / 5 121 / 6 145 | 6 344 | 6 828 | 7 460 | 253 576 |
| gc_cycles | 50k / 100k / 200k | 10 / 21 / 43 | 4 096 | 6 184 | 6 024 | 5 988 | 245 768 |
| gc_exceptions | 120k / 240k / 480k | 9 / 19 / 39 | 4 096 | 6 088 | 5 992 | 6 120 | 222 024 |
| gc_lines | 60k / 120k / 240k | 6 / 12 / 25 | 4 173 | 6 324 | 6 608 | 6 512 | 138 636 |
| gc_maps | 16k / 32k / 64k | 11 / 23 / 46 | 4 098 | 6 112 | 6 160 | 6 056 | 229 468 |
| gc_modules | 24k / 48k / 96k | 14 / 28 / 56 | 4 101 | 5 860 | 6 072 | 5 980 | ~294 000 |
| gc_strings | 12k / 24k / 48k | 10 / 20 / 41 | 4 096 | 5 992 | 6 024 | 6 076 | 224 972 |
| gc_structs | 50k / 100k / 200k | 9 / 18 / 36 | 4 096 | 6 028 | 5 992 | 5 992 | 239 516 |

stdout equals the JVM expectation at all 24 runs; each run takes 0.13 to 0.28 s. The bounds are unchanged
(64 MiB absolute, `1.5 * rss(N2) + 8 MiB`, `rss(N2) + 8 MiB`). `heap_peak_kb` sits at the 4 MiB trigger plus
live data: gc_arrays' kept array is live growth by design.

### Stress levels measured for the slow cases (see `GcStressNativeTest`)

| Case | n | time | collections |
|---|---|---|---|
| range_large | 31 / 97 / 1009 | 3.1 / 1.1 / 0.2 s | 6 443 / 2 059 / 197 |
| array_self_print | 97 / 1009 / 4001 | >50 / 14.9 / 3.6 s | - / 2 386 / 601 |
| ledger `large` | 1 / 7 / 61 | 50.5 / 7.4 / 1.0 s | 573 413 / 81 916 / 9 400 |

The ledger `large` case at n = 1 (a collection before every one of its 573 413 allocations) produced
byte-identical stdout and report; it is run at n = 7 in the suite only for time.
