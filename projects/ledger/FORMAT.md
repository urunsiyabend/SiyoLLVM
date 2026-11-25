# ledger — behaviour specification

`ledger` reconciles a CSV file of bank transactions into a per-account and per-category report.
This document is the contract for every implementation and oracle. Strings are compared, measured and
sorted by **UTF-16 code units**, as Java's `String` does. "Length" in this document always means UTF-16
length.

## Command line

```
ledger <transactions.csv> <report.txt>
```

The program needs exactly two arguments. Any other count is a usage error (see Errors).

## Reading the input

- The file is read as UTF-8. Behaviour on invalid UTF-8 is unspecified; the fixtures are valid UTF-8.
- The text is split into lines the way Java's `BufferedReader.readLine` does:
  - A line ends at `\n`, `\r\n` or a lone `\r`.
  - A final line terminator does not produce an extra empty line.
  - An empty file has no lines.
- Lines are numbered from 1. The header is line 1.
- **Header.** Line 1 must be exactly `date,account,category,amount,memo`, with no BOM and no spaces. If the
  file has no lines, or its first line differs, the program fails with the bad-header error.
- **Blank lines.** A data line (line 2 onwards) that is empty after `trim` is skipped. It is not counted as
  a row.
- **Rows.** Every other data line is a *row*. The *total* is the number of rows.

`trim` removes leading and trailing characters whose code is ≤ U+0020, as Java's `String.trim` does.

## Splitting a row into fields

Scan the line from left to right, one UTF-16 unit at a time. At the start of each field:

- **Quoted field** (the field's first character is `"`). Read characters until the field ends:
  - `""` is one literal `"`.
  - A `"` followed by `,` ends the field. The comma is consumed and the next field starts.
  - A `"` at the end of the line ends the field. It is the last field.
  - A `"` followed by any other character makes the row fail with reason `malformed quotes`.
  - Reaching the end of the line inside the quotes makes the row fail with reason `unterminated quote`.

  The text of a quoted field is **not** trimmed.
- **Unquoted field** (any other first character, including a space). Read up to the next `,` or the end of
  the line. The field is that text after `trim`. A `"` inside an unquoted field is an ordinary character.

A line ending in `,` has a final empty field. An empty line cannot occur here, because blank lines are
skipped.

## Validating a row

Checks run in this order. The first failing check gives the row's reason, and the row is *rejected*. A
row that passes every check is *accepted*.

1. Quote errors from splitting: `unterminated quote` or `malformed quotes`.
2. The field count must be 5. Otherwise the reason is `expected 5 fields, found N`, where N is the
   number of fields.
3. **date** (field 1) must be exactly 10 characters of the form `YYYY-MM-DD`:
   - ASCII digits in positions 1–4, 6–7 and 9–10, and `-` in positions 5 and 8;
   - month 01–12;
   - day 01–31 (no calendar check).

   Otherwise the reason is `invalid date 'X'`, where X is the field.
4. **account** (field 2) must not be empty. Otherwise the reason is `empty account`.
5. **category** (field 3) must not be empty. Otherwise the reason is `empty category`.
6. **amount** (field 4) must match `-?[0-9]{1,10}(\.[0-9]{2})?`, with ASCII digits only. Otherwise the
   reason is `invalid amount 'X'`, where X is the field.
   - The value in cents is integer part × 100 plus the two decimal digits (0 when there are none).
   - A leading `-` makes it negative. `-0` and `-0.00` are 0.
7. **memo** (field 5) may be anything, including empty. It is not used in the report.

## Aggregation

These are computed over accepted rows only:

| Figure | Scope | Definition |
|---|---|---|
| count | per account, per category | number of accepted rows |
| credits | per account | sum of positive amounts |
| debits | per account | sum of negative amounts, so ≤ 0 |
| net | per account, per category | credits + debits, equivalently the sum of amounts |
| totals | whole file | sums of count, credits, debits and net over all accounts |
| period | whole file | smallest and largest date string, by UTF-16 comparison (which is chronological for valid dates) |

## Money format

A money value is written in cents as `[-]<integer>.<two digits>`. The integer part has no leading zeros
other than a single `0`. There are no thousands separators, and the minus sign appears only for negative
values.

Examples:

| Cents | Written |
|---|---|
| 0 | `0.00` |
| 5 | `0.05` |
| −1234 | `-12.34` |
| 123456789 | `1234567.89` |

## Report file

The report is written as UTF-8 to `<report.txt>`, replacing any existing file. Every line ends in `\n`.

Notation used below:

- `pad(s, w)` appends spaces to `s` until its length is `w`.
- `lpad(s, w)` prepends spaces until its length is `w`.
- Neither truncates a string that is already longer.
- Columns are joined with single spaces.

```
LEDGER REPORT
source: <transactions.csv argument as given>
rows: <accepted> accepted, <rejected> rejected, <total> total
period: <min date> to <max date>          (or `period: none` when nothing was accepted)

ACCOUNTS
pad("ACCOUNT", W) lpad("COUNT", 6) lpad("CREDITS", 14) lpad("DEBITS", 14) lpad("NET", 14)
pad(account, W) lpad(count, 6) lpad(credits, 14) lpad(debits, 14) lpad(net, 14)     (one per account)
pad("TOTAL", W) lpad(total count, 6) lpad(total credits, 14) lpad(total debits, 14) lpad(total net, 14)

CATEGORIES
pad("CATEGORY", V) lpad("COUNT", 6) lpad("NET", 14)
pad(category, V) lpad(count, 6) lpad(net, 14)                                          (one per category)

REJECTED
line <n>: <reason>                                                                     (one per rejected row)
```

In this layout, the empty lines are literal empty lines (a bare `\n`).

**Sorting and widths:**

- **Account rows** are sorted by account name ascending, using UTF-16 code-unit comparison. Ties cannot
  occur.
- **Category rows** are sorted by net descending, then by category name ascending.
- **Widths:**
  - W is the larger of 7 and the longest account name.
  - V is the larger of 8 and the longest category name.

**Special cases:**

- With no accepted rows:
  - the ACCOUNTS section has its header line and a TOTAL line of zeros (count `0`, money `0.00`);
  - the CATEGORIES section has only its header line.
- When no row was rejected, the REJECTED section is the single line `none`.
- Rejected rows are listed in line order.

## Standard output and exit status

On success, the report is written and three lines go to standard output:

```
ledger: <accepted> accepted, <rejected> rejected
net: <total net>
report: <report.txt argument as given>
```

The exit status is 0 when no row was rejected and 3 otherwise.

## Errors

On any of these errors:

- one line goes to standard error;
- nothing goes to standard output;
- the report is not written;
- the exit status is 2.

| Condition | Standard error |
|---|---|
| wrong argument count | `usage: ledger <transactions.csv> <report.txt>` |
| input cannot be read | `ledger: cannot read <path>: <message>` |
| no lines, or wrong header | `ledger: <path>: bad header` |
| report cannot be written | `ledger: cannot write <report>: <message>` |

`<message>` is the I/O error message as the Java reference reports it, for example
`<path> (No such file or directory)`.
