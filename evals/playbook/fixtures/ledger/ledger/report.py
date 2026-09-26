import csv
from ledger.money import parse_amount


def load_entries(path):
    with open(path, newline="") as f:
        return [(row["category"], parse_amount(row["amount"])) for row in csv.DictReader(f)]


def total_by_category(entries):
    totals = {}
    for category, cents in entries:
        totals[category] = totals.get(category, 0) + cents
    return totals
