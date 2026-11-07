# ledger

`ledger` reconciles a CSV file of bank transactions into a per-account and per-category report. It is
SiyoLLVM's milestone application: a multi-module Siyo program that SiyoLLVM compiles to a native executable,
checked case by case against the JVM reference (`siyoc run`) on the same sources.

The behaviour contract is [FORMAT.md](FORMAT.md).

## Usage

```
ledger <transactions.csv> <report.txt>
```

The program writes the report to `<report.txt>` and prints a three-line summary. The exit status is:

- 0 when every row was accepted;
- 3 when some rows were rejected (the report is still written);
- 2 on an error: wrong argument count, unreadable input, bad header or unwritable report. Errors write one
  line to stderr, nothing to stdout, and no report.

## Build

From the repository root, after `mvn package`:

```sh
bin/siyollvm build projects/ledger/src/main.siyo -o ledger
./ledger transactions.csv report.txt
```

The same sources run on the JVM reference: `java -jar siyo-compiler-0.9.0.jar run projects/ledger/src/main.siyo …`.

## Modules

| File | Role |
|---|---|
| `src/main.siyo` | arguments, reading, header check, orchestration, errors and exit status |
| `src/csv.siyo` | splitting one line into fields (quoted and unquoted), `Split` |
| `src/model.siyo` | `Transaction`, row validation in FORMAT.md's order, amount parsing into cents |
| `src/ledger.siyo` | `Book`, `Account`, `Category`: aggregation into maps of structs |
| `src/report.siyo` | money format, sorting (insertion sort), padding, the report text |

## Tests

Each directory in `tests/` is a case: `args` (one argument per line), `files/` (inputs, copied into a fresh
run directory) and the expectations `expected.stdout`, `expected.stderr`, `expected.exit` and, when the run
writes the report, `expected.report`.

- `tools/oracle-jvm.sh [case...]` regenerates the expectations with the JVM reference, running each case from
  a scratch copy (build the reference first with `scripts/reference-oracle.sh build`). Review the diff before
  committing.
- `tools/gen_large.py` regenerates `tests/large/files/large.csv` (fixed seed, standard library only).
- `LedgerAppNativeTest` (`mvn -B verify -Pnative`) builds the application natively once and runs every case
  under `LC_ALL=C`, comparing stdout, stderr, exit status and the report byte for byte; for a case without
  `expected.report` it checks that no report was written (a report path the case provides as a directory must
  stay a directory).
- `LedgerOracleNativeTest` runs the independent Python oracle (`oracle/ledger.py`, written from FORMAT.md only)
  on every case and compares it with the same expectations.

## Limits

- Amounts are `long` cents; at most 10 integer digits per amount (FORMAT.md). Sums are not checked for
  overflow; the contract's inputs stay far below it.
- Sorting is an insertion sort over the distinct account and category names (quadratic in their number, not in
  the number of rows).
- Memory is never reclaimed during a native run (docs/SUBSET.md), so peak memory grows with the input (about
  87 bytes of RSS per additional input byte, see Resource use).
- Subset workarounds, each noted in the source: elements of arrays returned from another module are bound to
  typed variables; a struct read from a map is passed to a function taking a struct parameter so that its
  fields are typed (a map local inferred from a struct field has `any` values, docs/SUBSET.md); the `sort`
  builtin (which takes a lambda) is replaced by a hand-written sort.

## Resource use

Measured on 2026-11-07 (x86_64, glibc, clang 19 via `SIYOLLVM_CLANG`), each run in a scratch copy of the input,
inside the automation's bounded scope (768 MiB, no swap), with `/usr/bin/time -v`. "large" is
`tests/large/files/large.csv` (5 601 lines, 313 141 bytes); "large ×10" is its header plus its 5 600 data lines
ten times (56 001 lines, 3 131 104 bytes; generated in scratch, not committed).

| Input | Program | Peak RSS | Wall time | Exit |
|---|---|---|---|---|
| large | native `ledger` | 28 404 KB | 0.04 s | 3 |
| large | JVM reference (`siyoc run`) | 67 060 KB | 0.18 s | 3 |
| large ×10 | native `ledger` | 268 468 KB | 0.40 s | 3 |
| large ×10 | JVM reference (`siyoc run`) | 100 840 KB | 0.34 s | 3 |

Exit 3 is expected (the inputs have rejected rows). For each input the native and JVM stdout and reports are
byte-identical, and the native report on large equals `tests/large/expected.report`. Native peak RSS grows
roughly linearly with the input (×9.5 for ×10 input) because nothing is freed; the JVM's garbage collector keeps
it nearly flat. Commands (from the repository root `$REPO`; `B` is the bounded helper; `$S` is a scratch
directory holding copies of `large.csv` in `native/` and `jvm/`):

```sh
B="python3 $HOME/.hermes/automation/siyollvm/bounded.py --"
$B bin/siyollvm build projects/ledger/src/main.siyo -o $S/ledger
cd $S/native && env -u LANG LC_ALL=C $B /usr/bin/time -v ../ledger large.csv out.txt
cd $S/jvm && env -u LANG LC_ALL=C $B /usr/bin/time -v \
    java -jar ~/.cache/siyollvm-oracle/siyoc-02c2262/target/siyo-compiler-0.9.0.jar \
    run "$REPO/projects/ledger/src/main.siyo" large.csv out.txt
# large ×10:
{ cat large.csv; for i in 2 3 4 5 6 7 8 9 10; do tail -n +2 large.csv; done; } > large10.csv
```

Bounded evidence (receipts: child return code 3, `oom_kill_delta` 0), under
`~/.hermes/automation/siyollvm/bounded-runs/`: build `20261107-090806-d40a1dd1…`, native large
`20261107-090812-d2412243…`, JVM large `20261107-090812-205b58a0…`, native ×10 `20261107-090813-643982cc…`,
JVM ×10 `20261107-090813-2329d870…`.
