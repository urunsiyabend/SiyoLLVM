# Resource measurements for projects/ledger

Raw artefacts of the measurements quoted in `projects/ledger/README.md` ("Resource use"). x86_64, clang 19.1.7,
`LC_ALL=C`. On the measuring host every command ran inside a 768 MiB bounded scope (`bounded.py --`); the
`receipt-*.json` / `summary-*.json` files are that helper's records (child return code 3 is the ledger's
expected exit for the large input; `oom_kill_delta` is 0 in every receipt). Paths inside them are host paths.

| File | Content |
|---|---|
| `receipt-build.json`, `summary-build.json` | `siyollvm build projects/ledger/src/main.siyo -o $S/ledger` |
| `time-native.txt`, `receipt-native.json`, `summary-native.json` | native, `large.csv` (313 141 B) |
| `time-jvm.txt`, `receipt-jvm.json`, `summary-jvm.json` | JVM reference, `large.csv` |
| `time-native10.txt`, `receipt-native10.json`, `summary-native10.json` | native, `large10.csv` (3 131 104 B) |
| `time-jvm10.txt`, `receipt-jvm10.json`, `summary-jvm10.json` | JVM reference, `large10.csv` |

The `time-*.txt` files are the `/usr/bin/time -v` output (stderr of the run).

Commands (`$REPO` repository root, `$S` scratch directory with `large.csv` copied into `native/` and `jvm/`):

```sh
bin/siyollvm build projects/ledger/src/main.siyo -o $S/ledger
cd $S/native && env -u LANG LC_ALL=C /usr/bin/time -v ../ledger large.csv out.txt
cd $S/jvm && env -u LANG LC_ALL=C /usr/bin/time -v \
    java -jar ~/.cache/siyollvm-oracle/siyoc-02c2262/target/siyo-compiler-0.9.0.jar \
    run "$REPO/projects/ledger/src/main.siyo" large.csv out.txt
# large x10 (header + data lines x10), same two run commands with large10.csv:
{ cat large.csv; for i in 2 3 4 5 6 7 8 9 10; do tail -n +2 large.csv; done; } > large10.csv
```
