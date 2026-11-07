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
  `expected.report` it checks that no report was written.

## Limits

- Amounts are `long` cents; at most 10 integer digits per amount (FORMAT.md). Sums are not checked for
  overflow; the contract's inputs stay far below it.
- Sorting is an insertion sort over the distinct account and category names (quadratic in their number, not in
  the number of rows).
- Memory is never reclaimed during a native run (docs/SUBSET.md): the large fixture (5 600 lines, 313 KB)
  peaks at about 28 MB RSS.
- Subset workarounds, each noted in the source: elements of arrays returned from another module are bound to
  typed variables; a struct read from a map, or a struct field read through another module's struct, is
  passed to a function taking a struct parameter so that its fields are typed; the `sort` builtin (which takes
  a lambda) is replaced by a hand-written sort.
