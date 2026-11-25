# Memory-bound workloads (R1)

Each directory is one workload for `MemoryBoundsNativeTest`
(`src/test/java/siyollvm/nativegate/`). Its `main.siyo` (plus any modules it
imports) takes N as its first argument, allocates and drops memory in
proportion to N while keeping only a small live set, and prints deterministic
checksum lines.

| Workload | What it churns |
|---|---|
| `gc_strings` | a per-iteration local built with `+=`, then joined again |
| `gc_arrays` | arrays created, filled with boxed ints, read and dropped; one `int[]` of small ints grows for the whole run and stays live |
| `gc_maps` | a `Map<string, int>` rebuilt, edited and iterated every iteration |
| `gc_structs` | nested structs with string fields created, updated, dropped |
| `gc_cycles` | struct-struct, array-self and struct-array cycles, dropped |
| `gc_exceptions` | string payloads thrown two calls deep and caught; a failing `parseInt` in a `try` expression |
| `gc_modules` | all allocation inside the imported module `churn.siyo`, whose globals stay live |
| `gc_lines` | writes a 500-line file once, then reads it back with `io.readLines` and aggregates it into a 10-key map, N / 500 times (N lines read in all) |

`gc_lines` deviates from "a file of N lines" on purpose: `io.readLines` of an
N-line file holds O(N) data live, which no collector can bound, so the file
has a fixed size and N counts the lines read.

`sizes` lists N1 N2 N3. `expected-<N>.stdout` is the stdout of the JVM
reference (siyoc 0.9.0 at SiyoCompiler 02c2262, see
`scripts/reference-oracle.sh`) for that N, generated with

```sh
python3 ~/.hermes/automation/siyollvm/bounded.py -- sh scripts/memory-oracle.sh src/test/resources/memory/<name>
```

which runs `scripts/reference-oracle.sh run <dir>/main.siyo <N>` for each N in
`sizes` under `LC_ALL=C`, requires exit 0 and empty stderr, and renames
`main.expected` to `expected-<N>.stdout`. After changing a workload or its
sizes, regenerate and review its expectations.
