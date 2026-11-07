#!/usr/bin/env python3
"""Independent Python oracle for projects/ledger.

Implemented only from projects/ledger/FORMAT.md. Standard library only.

    python3 ledger.py <transactions.csv> <report.txt>
"""

import os
import re
import sys

HEADER = "date,account,category,amount,memo"
USAGE = "usage: ledger <transactions.csv> <report.txt>"
AMOUNT_RE = re.compile(r"-?[0-9]{1,10}(\.[0-9]{2})?", re.ASCII)
ASCII_DIGITS = "0123456789"


class RowError(Exception):
    pass


def u16len(s):
    return len(s.encode("utf-16-le", "surrogatepass")) // 2


def u16key(s):
    # Big-endian UTF-16 byte order equals UTF-16 code-unit order.
    return s.encode("utf-16-be", "surrogatepass")


def jtrim(s):
    """Java String.trim: strip leading/trailing chars whose code is <= U+0020."""
    start, end = 0, len(s)
    while start < end and ord(s[start]) <= 0x20:
        start += 1
    while end > start and ord(s[end - 1]) <= 0x20:
        end -= 1
    return s[start:end]


def read_lines(text):
    """Split like java.io.BufferedReader.readLine: \\n, \\r\\n or lone \\r."""
    lines = []
    i, n, start = 0, len(text), 0
    while i < n:
        c = text[i]
        if c == "\n":
            lines.append(text[start:i])
            i += 1
            start = i
        elif c == "\r":
            lines.append(text[start:i])
            i += 1
            if i < n and text[i] == "\n":
                i += 1
            start = i
        else:
            i += 1
    if start < n:
        lines.append(text[start:])
    return lines


def split_fields(line):
    fields = []
    pos, n = 0, len(line)
    while True:
        if pos < n and line[pos] == '"':
            pos += 1
            buf = []
            while True:
                if pos >= n:
                    raise RowError("unterminated quote")
                c = line[pos]
                if c != '"':
                    buf.append(c)
                    pos += 1
                    continue
                # c is a quote
                if pos + 1 >= n:
                    fields.append("".join(buf))
                    return fields
                nxt = line[pos + 1]
                if nxt == '"':
                    buf.append('"')
                    pos += 2
                elif nxt == ",":
                    fields.append("".join(buf))
                    pos += 2
                    break
                else:
                    raise RowError("malformed quotes")
        else:
            idx = line.find(",", pos)
            if idx < 0:
                fields.append(jtrim(line[pos:]))
                return fields
            fields.append(jtrim(line[pos:idx]))
            pos = idx + 1


def is_ascii_digits(s):
    return len(s) > 0 and all(c in ASCII_DIGITS for c in s)


def valid_date(d):
    if u16len(d) != 10 or len(d) != 10:
        return False
    if d[4] != "-" or d[7] != "-":
        return False
    y, m, dd = d[0:4], d[5:7], d[8:10]
    if not (is_ascii_digits(y) and is_ascii_digits(m) and is_ascii_digits(dd)):
        return False
    return 1 <= int(m) <= 12 and 1 <= int(dd) <= 31


def parse_amount(a):
    if AMOUNT_RE.fullmatch(a) is None:
        return None
    neg = a.startswith("-")
    body = a[1:] if neg else a
    if "." in body:
        ip, dp = body.split(".")
        cents = int(ip) * 100 + int(dp)
    else:
        cents = int(body) * 100
    return -cents if neg else cents


def validate(line):
    """Return (date, account, category, cents) or raise RowError(reason)."""
    fields = split_fields(line)
    if len(fields) != 5:
        raise RowError("expected 5 fields, found %d" % len(fields))
    date, account, category, amount, _memo = fields
    if not valid_date(date):
        raise RowError("invalid date '%s'" % date)
    if account == "":
        raise RowError("empty account")
    if category == "":
        raise RowError("empty category")
    cents = parse_amount(amount)
    if cents is None:
        raise RowError("invalid amount '%s'" % amount)
    return date, account, category, cents


def money(c):
    sign = "-" if c < 0 else ""
    a = -c if c < 0 else c
    return "%s%d.%02d" % (sign, a // 100, a % 100)


def pad(s, w):
    return s + " " * max(0, w - u16len(s))


def lpad(s, w):
    return " " * max(0, w - u16len(s)) + s


def io_message(path, exc):
    # Java's FileInputStream/FileOutputStream form: "<path> (<strerror>)".
    if exc.errno is not None:
        reason = os.strerror(exc.errno)
    else:
        reason = exc.strerror or str(exc)
    return "%s (%s)" % (path, reason)


def fail(msg):
    sys.stdout.flush()
    sys.stderr.buffer.write(msg.encode("utf-8", "surrogateescape") + b"\n")
    sys.stderr.buffer.flush()
    return 2


def main(argv):
    if len(argv) != 2:
        return fail(USAGE)
    csv_path, report_path = argv

    try:
        with open(csv_path, "rb") as f:
            data = f.read()
    except OSError as e:
        return fail("ledger: cannot read %s: %s" % (csv_path, io_message(csv_path, e)))

    text = data.decode("utf-8", "replace")
    lines = read_lines(text)
    if not lines or lines[0] != HEADER:
        return fail("ledger: %s: bad header" % csv_path)

    accepted = []
    rejected = []  # (line number, reason)
    total = 0
    for idx in range(1, len(lines)):
        line = lines[idx]
        if jtrim(line) == "":
            continue
        total += 1
        try:
            accepted.append(validate(line))
        except RowError as e:
            rejected.append((idx + 1, str(e)))

    # Aggregation
    accounts = {}  # name -> [count, credits, debits]
    categories = {}  # name -> [count, net]
    for date, account, category, cents in accepted:
        a = accounts.setdefault(account, [0, 0, 0])
        a[0] += 1
        if cents > 0:
            a[1] += cents
        elif cents < 0:
            a[2] += cents
        c = categories.setdefault(category, [0, 0])
        c[0] += 1
        c[1] += cents

    tot_count = sum(v[0] for v in accounts.values())
    tot_credits = sum(v[1] for v in accounts.values())
    tot_debits = sum(v[2] for v in accounts.values())
    tot_net = tot_credits + tot_debits

    out = []
    out.append("LEDGER REPORT")
    out.append("source: %s" % csv_path)
    out.append("rows: %d accepted, %d rejected, %d total" % (len(accepted), len(rejected), total))
    if accepted:
        dates = [r[0] for r in accepted]
        out.append("period: %s to %s" % (min(dates, key=u16key), max(dates, key=u16key)))
    else:
        out.append("period: none")
    out.append("")

    W = max([7] + [u16len(k) for k in accounts])
    out.append("ACCOUNTS")

    def acct_line(name, count, cr, db):
        return " ".join([pad(name, W), lpad(str(count), 6), lpad(money(cr), 14),
                         lpad(money(db), 14), lpad(money(cr + db), 14)])

    out.append(" ".join([pad("ACCOUNT", W), lpad("COUNT", 6), lpad("CREDITS", 14),
                         lpad("DEBITS", 14), lpad("NET", 14)]))
    for name in sorted(accounts, key=u16key):
        cnt, cr, db = accounts[name]
        out.append(acct_line(name, cnt, cr, db))
    out.append(acct_line("TOTAL", tot_count, tot_credits, tot_debits))
    out.append("")

    V = max([8] + [u16len(k) for k in categories])
    out.append("CATEGORIES")
    out.append(" ".join([pad("CATEGORY", V), lpad("COUNT", 6), lpad("NET", 14)]))
    for name in sorted(categories, key=lambda k: (-categories[k][1], u16key(k))):
        cnt, net = categories[name]
        out.append(" ".join([pad(name, V), lpad(str(cnt), 6), lpad(money(net), 14)]))
    out.append("")

    out.append("REJECTED")
    if rejected:
        for n, reason in rejected:
            out.append("line %d: %s" % (n, reason))
    else:
        out.append("none")

    report = "".join(l + "\n" for l in out).encode("utf-8", "surrogateescape")
    try:
        with open(report_path, "wb") as f:
            f.write(report)
    except OSError as e:
        return fail("ledger: cannot write %s: %s" % (report_path, io_message(report_path, e)))

    stdout = ("ledger: %d accepted, %d rejected\nnet: %s\nreport: %s\n"
              % (len(accepted), len(rejected), money(tot_net), report_path))
    sys.stdout.buffer.write(stdout.encode("utf-8", "surrogateescape"))
    sys.stdout.buffer.flush()
    return 0 if not rejected else 3


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
