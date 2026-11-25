# R1 Task 3: ledger application evidence (2026-11-25)

Measured on 2026-11-25 at HEAD c66bc5b (the collector of 77f5281 and 39235b1 plus the 24-byte size
class). The host clang was `$SIYOLLVM_CLANG` (siyo-llvm-19, clang 19.1.7) on Linux 7.0.0-38, x86_64.
Every run (build, generator, native executable, Python oracle, comparison) ran under its own bounded
768 MiB scope through `bounded.py`. Each run directory holds its receipt. Every receipt here shows
oom_kill_delta 0. The ledger exits 3 when it rejects rows, so the child return code is 3 and bounded.py
marks these runs `accepted=False` (see `bounded.txt`). The run's own exit code is copied from the receipt
into `exit`.

## How it was run

- Executable: `bin/siyollvm build projects/ledger/src/main.siyo -o $SCRATCH/ledger`, bounded
  (sha256 in `executables.sha256`).
- Inputs: `gen.py` builds `input.csv` for scale factor N the same way the supervisor acceptance did
  (`~/.hermes/automation/siyollvm/acceptance-20261125/probe.py`): the header line of
  `projects/ledger/tests/large/files/large.csv`, then its data lines repeated N times. Sizes are 313 141,
  939 355 and 3 131 104 bytes, identical to the acceptance. The ×1 input is byte-identical to `large.csv`.
  The inputs are not committed; their hashes are in `inputs.sha256`.
- Each run: `run.sh DIR LABEL CMD...` runs `bounded.py -- sh -c 'exec env LC_ALL=C SIYO_RT_STATS=1
  /usr/bin/time -v -o time.txt CMD input.csv out.txt'` in DIR. It keeps `stdout`, `stderr`, `time.txt`
  (time -v), `receipt.json`, `bounded.txt` and `exit`.
- Oracle: `python3 -I projects/ledger/oracle/ledger.py input.csv out.txt` (independent Python
  implementation) on its own copy of the same input, in its own directory (`xN/oracle`).
- `compare.py` compares native and oracle byte for byte: exit code, stdout, stderr, and the report
  `out.txt`. Only native stderr's single trailing `siyo-rt:` statistics line is excluded. The result is in
  `xN/compare.json`.

Order: ×1, then ×3, then ×10 (each after the previous was seen to be small).

## NEW measurements (2026-11-25, HEAD c66bc5b, collector runtime)

| Scale | input bytes | peak RSS, time -v (KiB) | rss_peak_kb (VmHWM) | heap_peak_kb | collections | wall (s) | user+sys (s) | oracle match | oracle RSS (KiB) / wall (s) |
|---|---|---|---|---|---|---|---|---|---|
| ×1 | 313 141 | 7 344 | 7 508 | 4 927 | 6 | 0.02 | 0.02 | yes (exit 3/3, stdout, stderr, report) | 17 728 / 0.04 |
| ×3 | 939 355 | 9 372 | 9 468 | 6 637 | 19 | 0.07 | 0.07 | yes | 26 236 / 0.11 |
| ×10 | 3 131 104 | 19 948 | 20 296 | 16 186 | 34 | 0.26 | 0.26 | yes | 56 244 / 0.33 |

The `heap_peak_kb` above the 4 MiB trigger at ×10 is live data, not garbage. `readLines` reads the
whole input, so its lines (UTF-16 strings) stay reachable until the run ends. The next trigger is the
live size after a collection, so the heap can reach about twice the live input.

## HISTORICAL baseline (quoted, not re-measured)

From the supervisor acceptance of 2026-11-25 at 2f5a23a, with the never-free runtime:
`~/.hermes/automation/siyollvm/acceptance-20261125/application-summary.json` (`resource_runs`,
`/usr/bin/time -f '%M %e'`). That runtime is not re-run at ×10 here.

| Scale | input bytes | native RSS (KiB) | native wall (s) | Python oracle RSS (KiB) / wall (s) |
|---|---|---|---|---|
| ×1 | 313 141 | 28 404 | 0.04 | 17 632 / 0.04 |
| ×3 | 939 355 | 81 620 | 0.12 | 26 556 / 0.11 |
| ×10 | 3 131 104 | 268 416 | 0.39 | 56 300 / 0.34 |

At the same inputs, native peak RSS falls from 28 404 / 81 620 / 268 416 KiB to 7 344 / 9 372 /
19 948 KiB (about 3.9×, 8.7× and 13.5× less). From ×1 to ×10, the historical runtime grew about 87 KiB of RSS
per KiB of input, and the new runtime grows about 4.6 KiB. That growth is the input kept live by
`readLines`.

## Before/after wall time at ×1 (both measured today)

`ledger-old` is the same IR (`bin/siyollvm emit-llvm` at c66bc5b) linked by hand with the
pre-collector runtime, `git show 333165a:src/main/resources/siyollvm/runtime/siyo_rt.c`. The commits
since 333165a change only `siyo_rt.c` and `siyo_rt.h`, so the IR is the same. The link used the
compiler's own flags: `clang -O1 -fverify-intermediate-code -w -x ir ledger.ll -x c -std=c11 siyo_rt.c
-pthread`, bounded. Each executable ran 5 times, alternating (`timing/old-N`, `timing/new-N`). Every
run matched the oracle (`compare.json`).

| Runtime | wall (s), 5 runs | peak RSS time -v (KiB) | collections | heap_peak_kb | minor faults (run 1) |
|---|---|---|---|---|---|
| pre-collector (333165a) | 0.04 ×5 | 28 280 to 28 536 | 0 | 18 934 | 7 006 |
| collector (c66bc5b) | 0.02 to 0.03 | 7 228 to 7 476 | 6 | 4 927 | 2 123 |

time -v has 10 ms resolution, so this only shows that the collector does not slow the ledger. It is
slightly faster, probably from fewer page faults (2 123 against 7 006). It does not measure collector
overhead precisely.

## 24-byte size class (allocator change measured above)

With the one-byte end-pointer slack (39235b1), a 16-byte box took a 32-byte block. c66bc5b adds a
24-byte class (16- to 23-byte requests). The `n + 1` rule is unchanged. `gc_hooks_test.c` gains
`class_24()`, plus end-pointer checks at 17, 23 and 24 bytes and a survival check at 23 bytes.
RED: `class24-red.log` (receipt `class24-red-receipt.json`, child returncode 1, oom_kill_delta 0): the 3
`class 24` checks fail against 39235b1, and the new end-pointer checks already pass there. GREEN:
`class24-green.log` (`RuntimeGcNativeTest` and `MemoryBoundsNativeTest`, 15 tests, accepted; bounds
unchanged).
