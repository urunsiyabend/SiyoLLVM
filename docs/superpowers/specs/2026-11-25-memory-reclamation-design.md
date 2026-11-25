# Memory reclamation — design

Date: 2026-11-25
Status: approved under the renewed mandate in `docs/RUNTIME_READINESS_GOAL.md`. The reclamation
architecture is a routine design decision under that mandate. The process is non-interactive, so the
mandate itself serves as approval.

## Problem

Today every runtime allocation is a `malloc` that is never freed. The ledger application, measured on
2026-11-25 by the supervisor and stored in the automation acceptance evidence, peaks at:

| Input | Peak RSS |
|---|---|
| 313 141 bytes | 28 404 KiB |
| 939 355 bytes | 81 620 KiB |
| 3 131 104 bytes | 268 416 KiB |

A loop that builds and discards strings, arrays, maps or structs grows without bound. The goal
requires that realistic long-running data-processing loops reclaim memory, without changing semantics.

## Options considered

1. **Reference counting**, emitted as inc/dec operations in the IR.
   - Every load, store, argument, return, scope exit and exception path would gain bookkeeping.
   - Cycles (struct ↔ struct, array containing itself) leak without a cycle collector.
   - The risk is high, it touches every emitter path, and it changes the cost model everywhere.

   Rejected.
2. **Precise tracing GC with stack maps** (LLVM `gc.statepoint`).
   - Precise and moving-capable.
   - Requires statepoint lowering, relocation and a stack-map parser. A clang 15–19 IR text path is
     fragile, and the work is large.

   Rejected for now.
3. **Non-moving mark-sweep with conservative roots** (the Boehm-style design), owned by the runtime.
   - Objects never move, so every pointer the IR and the C runtime hold stays valid.
   - Cycles are collected.
   - No IR change is needed apart from making sure the roots are visible.
   - The cost: an integer that happens to look like a heap address keeps a block alive, which retains
     extra memory but is never unsafe.

   **Chosen.**

## Design

### Heap

- **Small blocks** (≤ 2 KiB) come from 64 KiB pages aligned to 64 KiB, one size class per page:
  16, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024, 1536 and 2048 bytes.
- **Large blocks** are allocated individually, aligned to 64 KiB.
- **Page table.** A page table (an open-addressing hash from 64 KiB page base to page descriptor) maps
  any address to its page. Every 64 KiB page a large block spans maps to that block's descriptor. Any
  pointer, including an interior one, therefore resolves to its block, or to nothing for stack, static
  or `malloc` memory and literal constants.
- **Per-block state.** Each block records whether it is allocated, whether it is marked, and its kind:
  - `SCAN`: may contain pointers, so every aligned word is scanned conservatively;
  - `NOSCAN`: holds raw bytes: string bytes in `SiyoBuf`, box payloads, map slot index arrays.
- **Zeroing.** Every block is zeroed when allocated.
- **Freeing.** Blocks are never freed explicitly. Code that used `realloc` or `free` on runtime memory
  (array and map growth) allocates the new block, copies, and leaves the old block to the collector.
- **Scratch memory.** `siyo_rt_scratch` (C temporaries of raw units and bytes) stays on `malloc`/`free`.
  It must never hold a heap pointer across an allocation; a test asserts this convention.

### Roots

- **Program-thread stack.** Scanned from the collector's current stack pointer up to the top of the
  program thread's stack. The top comes from `pthread_getattr_np` at thread start. Callee-saved registers
  are spilled first with `__builtin_unwind_init` and a `setjmp` buffer in the collector's frame.
- **Static data.** The program's `.data` and `.bss` (glibc/ELF symbols `__data_start` and `_end`) are
  scanned. This covers the IR's module globals and the runtime's own statics, such as the box cache.
- **Thread-local roots.** Thread-local storage is not in that range, so the pending-exception payload is
  registered explicitly as a root.
- **Main thread.** The main thread never touches heap objects; it only creates and joins the program
  thread.

### Collection

- A collection runs at allocation time on the program thread when bytes allocated since the last
  collection exceed `max(4 MiB, live bytes after the last collection)`. The heap therefore stays within
  about twice the live data plus 4 MiB.
- **Mark phase.** It uses an explicit mark stack, which is a growable `malloc`'d array, never the C stack.
  Marking deep structures therefore cannot overflow the stack.
- **Sweep phase.**
  - Unmarked small blocks go back onto their page's free list.
  - Unmarked large blocks are returned with `free`.
  - Completely empty small pages beyond a small reserve are returned to the OS.
- **Out of memory.** The existing message, `java.lang.OutOfMemoryError`, is unchanged.

### Diagnostics

- **`SIYO_GC_STRESS=<n>`** collects every n allocations. It is a test and debug mode that makes a missing
  root show up immediately, as a wrong output or a crash.
- **`SIYO_RT_STATS=1`** makes the program print one line to stderr at exit:

  ```
  siyo-rt: collections=<c> heap_peak_kb=<h> rss_peak_kb=<r>
  ```

  `rss_peak_kb` comes from `getrusage`. Tests use this to measure memory without external tools.

### Unchanged

- Value representation, tags, string buffers (append in place still works, because a buffer is only
  reclaimed when no header references it), aliasing and identity.
- Exceptions, the IR and the CLI.
- Program output, which is identical to before in every case.

## Risks and how they are tested

| Risk | Test |
|---|---|
| A missing root: a heap pointer held only in a place that is not scanned | The full native corpus, module cases and ledger fixtures under `SIYO_GC_STRESS=1` must produce byte-identical outputs. |
| Interior or derived pointers produced by clang -O1 | Same as above; interior lookup is part of the design. |
| False retention from conservative scanning | Tolerated. The memory-bound tests assert bounds with room to spare and check growth across N, not exact sizes. |
| Collector cost | Measured: wall time at increasing N, plus ledger timings. |

## Measuring success

Workload programs run at increasing N:

- string churn, array churn, map churn and struct churn, including struct cycles;
- exceptions thrown and caught in a loop;
- a cross-module allocating function;
- a line-processing loop over a file.

Each assertion holds both an absolute peak-RSS bound and a ratio (peak at the largest N over peak at a
middle N). Outputs at every N are checked against reference expectations generated by the JVM oracle.

The ledger is re-measured at ×1, ×3 and ×10 input.

New measurements are recorded in `docs/evidence/r1-gc/`. The historical 2026-11-25 baseline stays where
the supervisor recorded it and is quoted, not re-labelled.
