# ledger oracle (Python)

`ledger.py` is a second, independent implementation of `projects/ledger/FORMAT.md`, written in Python
using only the standard library:

```
python3 ledger.py <transactions.csv> <report.txt>
```

It exists to check the committed expectations in `projects/ledger/tests/*/expected.*` (produced by the
JVM reference) against a reading of the contract that shares no code and no author context with the Siyo
application. `src/test/java/siyollvm/nativegate/LedgerOracleNativeTest.java` runs it on every case and
asserts byte equality of stdout, stderr, exit status and report.

## Independence

The oracle was written from `FORMAT.md` and the *input* side of the fixtures only (`tests/<case>/args`
and `tests/<case>/files/`). `projects/ledger/src/`, `projects/ledger/tools/`, `projects/ledger/README.md`
and every `expected.*` file were not opened until the oracle was complete; the expected files were first
read when the agreement test was run.

## Python vs Java details the contract requires

- Lengths are UTF-16 code units (`len(s.encode('utf-16-le')) // 2`), not code points.
- Ordering is UTF-16 code-unit order: keys are `s.encode('utf-16-be')`, so supplementary characters
  (surrogates D800-DFFF) sort before U+E000-U+FFFF, unlike Python's code-point order.
- `trim` strips characters <= U+0020 only (Java `String.trim`), not `str.strip()`.
- Lines are split like `BufferedReader.readLine` (`\n`, `\r\n`, lone `\r`; no extra empty line after a
  final terminator), not `str.splitlines()` (which also splits on U+2028, U+0085, form feed, ...).
- Digits are ASCII `0-9` only (`str.isdigit()` would accept fullwidth digits).
- I/O error messages use Java's `FileInputStream`/`FileOutputStream` form `<path> (<strerror>)`,
  with `os.strerror(errno)`.
- Output is UTF-8 with `\n`, written as bytes; arguments are echoed as their original bytes.

## Ambiguities and the interpretation taken

1. **Splitting is per UTF-16 unit, the oracle splits per code point.** The only significant characters
   (`"`, `,`) are ASCII and can never be part of a surrogate pair, so the two are equivalent.
2. **A quoted field that ends with `",` at the very end of the line** (`...,"trailing",`) is followed by
   a final empty unquoted field, i.e. the row has one more field ("A line ending in `,` has a final empty
   field").
3. **Data lines are not trimmed before splitting.** Only the blank-line check uses `trim`; field
   splitting sees the raw line, so leading spaces before a `"` make the field unquoted.
4. **Date length** is checked as UTF-16 length 10; any non-ASCII character fails the digit/dash check
   anyway, so there is no observable difference from code-point length.
5. **"period"** uses the date strings of accepted rows only (the aggregation table says all figures are
   over accepted rows).
6. **Paths in messages** are the arguments exactly as given. Java's `java.io.File` would normalise some
   paths (repeated or trailing `/`) in the `(<strerror>)` message; the oracle does not, and no fixture
   exercises it.
7. **Order of error checks**: argument count, then reading the input, then the header, then writing the
   report. On a write error nothing goes to stdout (stdout is written only after the report).
8. **Invalid UTF-8** is unspecified; the oracle decodes with replacement characters (as Java's decoder
   does by default) but this is not tested.
9. **Category ordering** compares net as integers in cents (descending), then the name by UTF-16 order.
