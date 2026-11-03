"""Convert models.dev's api.json into Tollgate's price catalog.

Usage: update-prices.py <api.json> <source commit> <output.tsv>
"""

import datetime
import json
import sys
from decimal import ROUND_CEILING, Decimal

COLUMNS = ("input", "output", "reasoning", "cache_read", "cache_write")
NANO = Decimal("0.000000001")


def amount(value):
    if not isinstance(value, Decimal) or value < 0:
        raise ValueError(f"not a price: {value!r}")
    return format(value.quantize(NANO, rounding=ROUND_CEILING).normalize(), "f")


def rates(cost):
    filled = dict(cost)
    filled.setdefault("reasoning", cost["output"])
    filled.setdefault("cache_read", cost["input"])
    filled.setdefault("cache_write", cost["input"])
    return [amount(filled[column]) for column in COLUMNS]


def tiers(cost):
    for tier in cost.get("tiers", []):
        if tier["tier"]["type"] == "context":
            yield int(tier["tier"]["size"]), tier
    if "tiers" not in cost and "context_over_200k" in cost:
        yield 200_000, cost["context_over_200k"]


def rows(catalog):
    for provider, entry in catalog.items():
        for model, details in entry.get("models", {}).items():
            cost = details.get("cost")
            if not cost or "input" not in cost or "output" not in cost:
                continue
            if any(character in provider + model for character in "\t\r\n"):
                raise ValueError(f"unexpected characters in {provider}/{model}")
            try:
                found = [[provider, model, "0", *rates(cost)]]
                for from_tokens, tier in tiers(cost):
                    found.append([provider, model, str(from_tokens), *rates(tier)])
            except (KeyError, ValueError) as problem:
                print(f"skipping {provider}/{model}: {problem}", file=sys.stderr)
                continue
            yield from found


def main(source, commit, output):
    with open(source, encoding="utf-8") as file:
        catalog = json.load(file, parse_float=Decimal, parse_int=Decimal)
    today = datetime.date.today().isoformat()
    lines = [f"# models.dev {today} {commit}"]
    lines += ["\t".join(row) for row in sorted(rows(catalog), key=lambda row: (row[0], row[1], int(row[2])))]
    with open(output, "w", encoding="utf-8", newline="\n") as file:
        file.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main(*sys.argv[1:])
