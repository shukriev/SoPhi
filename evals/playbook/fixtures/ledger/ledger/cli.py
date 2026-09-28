import sys
from ledger.money import format_amount
from ledger.report import load_entries, total_by_category


def main(argv):
    entries = load_entries(argv[1])
    for category, cents in sorted(total_by_category(entries).items()):
        print(f"{category}: {format_amount(cents)}")
    print(f"TOTAL: {format_amount(sum(c for _, c in entries))}")


if __name__ == "__main__":
    main(sys.argv)
