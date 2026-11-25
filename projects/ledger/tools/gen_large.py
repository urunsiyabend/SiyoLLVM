#!/usr/bin/env python3
"""Generates projects/ledger/tests/large/files/large.csv.

Deterministic (fixed seed, standard library only): running it again gives the
same bytes. About 5 600 rows, a few per cent of them invalid, with Unicode
account, category and memo text, some quoted fields and a few blank lines.

    python3 projects/ledger/tools/gen_large.py [output-path]
"""
import os
import random
import sys

SEED = 20261107
ROWS = 5600

ACCOUNTS = [
    "Checking", "Savings", "Credit Card", "Brokerage", "Cash", "Joint Account",
    "Şirket Hesabı", "İş Bankası", "Ziraat", "Garanti BBVA", "ağaç Kooperatif",
    "銀行口座", "東京三菱", "工商银行", "💳 Travel Card", "😀 Wallet", "🏦 Reserve",
    "Ａ Fullwidth", "Ünal Ailesi", "Öğrenci Kartı", "zebra fund", "Acme, Inc.",
    "Main \"Gold\"", "Petty cash",
]
CATEGORIES = [
    "Groceries", "Rent", "Salary", "Utilities", "Dining", "Transport", "Travel",
    "Insurance", "Health", "Kira", "Market/Pazar", "食品", "交通", "☕ Kahve",
    "🎉 Parties", "Gifts, misc", "ｶﾌｪ", "Refunds",
]
WORDS = [
    "payment", "invoice", "transfer", "monthly", "weekly", "ödeme", "fatura",
    "alışveriş", "çay", "şehir", "寿司", "电费", "コーヒー", "🍕", "🚕", "✈️",
    "refund", "fee", "shop", "online", "store #12", "\"quoted\"", "a, b",
]


def quote(text):
    """CSV-quotes a field when it needs it (comma or quote), otherwise as is."""
    if "," in text or "\"" in text:
        return "\"" + text.replace("\"", "\"\"") + "\""
    return text


def date(rng):
    year = rng.choice([2024, 2025])
    return "%04d-%02d-%02d" % (year, rng.randint(1, 12), rng.randint(1, 28))


def amount(rng, category):
    if category in ("Salary", "Refunds"):
        cents = rng.randint(1000_00, 9000_00)
    else:
        cents = -rng.randint(1, 250_000)
        if rng.random() < 0.1:
            cents = -cents
    sign = "-" if cents < 0 else ""
    cents = abs(cents)
    if rng.random() < 0.15 and cents % 100 == 0:
        return "%s%d" % (sign, cents // 100)
    return "%s%d.%02d" % (sign, cents // 100, cents % 100)


def memo(rng):
    return " ".join(rng.choice(WORDS) for _ in range(rng.randint(0, 4)))


def valid_row(rng):
    category = rng.choice(CATEGORIES)
    return ",".join([date(rng), quote(rng.choice(ACCOUNTS)), quote(category),
                     amount(rng, category), quote(memo(rng))])


def invalid_row(rng):
    kind = rng.randrange(9)
    category = rng.choice(CATEGORIES)
    d, a, c, m = date(rng), quote(rng.choice(ACCOUNTS)), quote(category), amount(rng, category)
    if kind == 0:
        return ",".join([d.replace("-", "/"), a, c, m, "bad date"])
    if kind == 1:
        return ",".join([d[:5] + "13" + d[7:], a, c, m, "month 13"])
    if kind == 2:
        return ",".join([d, "", c, m, "no account"])
    if kind == 3:
        return ",".join([d, a, "", m, "no category"])
    if kind == 4:
        return ",".join([d, a, c, m + "5", "three decimals"]) if "." in m else ",".join([d, a, c, "x" + m, "letters"])
    if kind == 5:
        return ",".join([d, a, c, m])
    if kind == 6:
        return ",".join([d, a, c, m, "memo", "extra"])
    if kind == 7:
        return ",".join([d, a, c, m, "\"unterminated memo"])
    return ",".join([d, a, "\"" + category + "\"x", m, "malformed"])


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "..", "tests", "large", "files", "large.csv")
    rng = random.Random(SEED)
    lines = ["date,account,category,amount,memo"]
    for _ in range(ROWS):
        r = rng.random()
        if r < 0.004:
            lines.append(rng.choice(["", "   ", "\t"]))
        elif r < 0.035:
            lines.append(invalid_row(rng))
        else:
            lines.append(valid_row(rng))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="") as f:
        f.write("".join(line + "\n" for line in lines))


if __name__ == "__main__":
    main()
