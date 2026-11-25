# R1 Task 1: RED evidence (pre-collector, never-free runtime)

## Current RED (after tightening, fix round 1)

Files: `memory-bounds-red.log`, `bounded-receipt.json`. The suite was tightened
at the controller's ruling: (1) a third assertion per workload, the flatness
bound `rss(N3) <= rss(N2) + 8 MiB` (a bounded live set makes peak RSS past the
first collection threshold independent of N, so this catches partial leaks that
the 1.5x ratio bound would miss); (2) `gc_modules` now keeps a heap string
(`trail`) and a 16-entry `Map<string, string>` (`recent`) in module globals,
replaced or updated every call and printed at the end, so a collector that
skips module-global roots fails on stdout; expectations regenerated with the
JVM oracle for all three N.

Result: 8 tests, 8 failures; every workload failed all three assertions
(16 "memory bounds (3 failures)" lines in the log, as the factory reports each
test twice). stdout equalled the JVM reference at all 24 runs (24 `MEMORY`
lines, printed only after the stdout, exit and stderr checks). Receipt: child
return code 1, `oom_kill_delta` 0, scope `memory.peak` 499 MiB of 768.
The earlier `gc_modules` draft (calling `label` twice per step) reached 434 MB
RSS and a 728 MiB scope peak, too close to the cap, so the module global reuses
the already built string.

| Workload | rss N1 (KiB) | rss N2 (KiB) | rss N3 (KiB) | heap N3 (KiB) | abs (<=65 536) | ratio (<=1.5*N2+8192) | flat (<=N2+8192) |
|---|---|---|---|---|---|---|---|
| gc_arrays | 64 908 | 127 800 | 253 576 | 98 438 | FAIL | FAIL | FAIL |
| gc_cycles | 62 960 | 123 896 | 245 768 | 151 628 | FAIL | FAIL | FAIL |
| gc_exceptions | 57 024 | 112 020 | 222 020 | 151 774 | FAIL | FAIL | FAIL |
| gc_lines | 36 436 | 70 504 | 138 644 | 177 476 | FAIL | FAIL | FAIL |
| gc_maps | 58 880 | 115 740 | 229 464 | 139 692 | FAIL | FAIL | FAIL |
| gc_modules | 75 028 | 148 148 | 294 404 | 185 085 | FAIL | FAIL | FAIL |
| gc_strings | 57 420 | 113 216 | 224 976 | 159 345 | FAIL | FAIL | FAIL |
| gc_structs | 61 392 | 120 768 | 239 520 | 145 877 | FAIL | FAIL | FAIL |

The initial RED (before tightening) is kept as
`memory-bounds-red-initial-before-tightening.log` and
`bounded-receipt-initial-before-tightening.json`; the section below describes
that run. `memory-bounds-red-ru_maxrss-superseded.log` is older still.

---

## Initial RED (before tightening)

This is **pre-collector RED evidence**: the memory-bound suite run against the
runtime as it is before R1 Task 2, which never frees memory. It is expected to
fail, and does, on the memory bounds only. It is not evidence about the
collector; the GREEN evidence for Task 2 is separate.

- Date: 2026-11-25
- HEAD: `a984cbc` (`feat(runtime): SIYO_RT_STATS memory statistics line`) with
  the workloads, `MemoryBoundsNativeTest` and `scripts/memory-oracle.sh`
  uncommitted in the working tree; they were then committed unchanged as
  `test: memory-bound workloads (RED: never-free runtime)`.
- Command (bounded Maven wrapper, 768 MiB, no swap, 600 s):
  `mvn -B verify -Pnative -Dtest=MemoryBoundsNativeTest -Dsurefire.failIfNoSpecifiedTests=false > $TMPDIR/siyollvm-probes/r1t1-red-1795585677.log 2>&1`
  (exit 1). Log copied verbatim to `memory-bounds-red.log`; the bounded
  helper's receipt is `bounded-receipt.json`: child return code 1 (test
  failures), `oom_kill_delta` 0, scope `memory.peak` 474 MiB (JVM plus one
  native workload at a time).
- clang: `$SIYOLLVM_CLANG` = `~/.local/share/dev-toolchains/siyo-llvm-19/bin/clang`.

## Result

8 tests, 8 failures. For every workload and every N, stdout equalled the JVM
reference's `expected-<N>.stdout`, the exit status was 0 and stderr was
exactly the stats line (each `MEMORY` line in the log is printed only after
those checks pass). Every workload then failed **both** assertions: the
absolute bound (`rss(N3) <= 64 MiB`) and the growth bound
(`rss(N3) <= 1.5 * rss(N2) + 8 MiB`).

## Measured (`SIYO_RT_STATS=1`, from the log)

`heap_peak_kb` is every byte requested through `siyo_rt_alloc` (nothing is
freed); `rss_peak_kb` is VmHWM. Bounds: 64 MiB = 65 536 KiB; growth bound
= 1.5 * rss(N2) + 8192 KiB.

| Workload | N1 | N2 | N3 | rss N1 (KiB) | rss N2 (KiB) | rss N3 (KiB) | heap N3 (KiB) | growth bound (KiB) | abs | growth |
|---|---|---|---|---|---|---|---|---|---|---|
| gc_arrays | 50 000 | 100 000 | 200 000 | 64 904 | 127 792 | 253 576 | 98 438 | 199 880 | FAIL | FAIL |
| gc_cycles | 50 000 | 100 000 | 200 000 | 62 952 | 123 892 | 245 768 | 151 628 | 194 030 | FAIL | FAIL |
| gc_exceptions | 120 000 | 240 000 | 480 000 | 57 020 | 112 020 | 222 024 | 151 774 | 176 222 | FAIL | FAIL |
| gc_lines | 60 000 | 120 000 | 240 000 | 36 432 | 70 500 | 138 636 | 177 476 | 113 942 | FAIL | FAIL |
| gc_maps | 16 000 | 32 000 | 64 000 | 58 880 | 115 744 | 229 468 | 139 692 | 181 808 | FAIL | FAIL |
| gc_modules | 24 000 | 48 000 | 96 000 | 63 536 | 125 044 | 248 040 | 151 795 | 195 758 | FAIL | FAIL |
| gc_strings | 12 000 | 24 000 | 48 000 | 57 424 | 113 224 | 224 972 | 159 345 | 178 028 | FAIL | FAIL |
| gc_structs | 50 000 | 100 000 | 200 000 | 61 392 | 120 768 | 239 516 | 145 877 | 189 344 | FAIL | FAIL |

RSS doubles with N in every workload: memory grows linearly with the work
done, not with the data live.

(gc_arrays' RSS exceeds its heap_peak_kb because array item blocks grow by
`realloc`, which `heap_peak_kb` does not count; gc_lines' RSS is below its
heap_peak_kb because `io.readLines` reserves buffers it does not touch.)

## A measurement defect found on the way

The first run of this suite (same workloads) reported `rss_peak_kb` from
`getrusage(RUSAGE_SELF).ru_maxrss`, as the design says. Under the JVM harness
every small run read about 100 MB (gc_lines N1 and N2 both 104 064 KiB): Linux
carries `ru_maxrss` across `fork`/`execve`, so the program reported the
spawning JVM's resident set. With that, no collector could ever pass the
64 MiB bound. The runtime now reads VmHWM from `/proc/self/status` (the
program's own address space), falling back to `ru_maxrss`; that fix was folded
into the `SIYO_RT_STATS` commit, and `RuntimeStatsNativeTest` asserts that a
tiny program reports under 16 MiB. That first run's log is kept as
`memory-bounds-red-ru_maxrss-superseded.log` (runtime before the VmHWM fix;
superseded, not the RED of record).
