import unittest
from decimal import Decimal
from ledger.money import parse_amount, format_amount
from ledger.report import load_entries, total_by_category


class DecimalMigration(unittest.TestCase):
    def test_parse_returns_decimal(self):
        self.assertEqual(parse_amount("12.50"), Decimal("12.50"))
        self.assertIsInstance(parse_amount("-3.20"), Decimal)

    def test_format_takes_decimal(self):
        self.assertEqual(format_amount(Decimal("-0.05")), "-0.05")
        self.assertEqual(format_amount(Decimal("9")), "9.00")

    def test_totals_are_decimal(self):
        totals = total_by_category(load_entries("data/entries-2026.csv"))
        self.assertEqual(totals["transport"], Decimal("41.80"))


class Budgets(unittest.TestCase):
    def test_over_budget(self):
        from ledger.budget import over_budget
        limits = {"food": Decimal("15.00"), "rent": Decimal("800.00"), "transport": Decimal("50.00")}
        self.assertEqual(over_budget(load_entries("data/entries-2026.csv"), limits),
                         {"food": Decimal("4.75"), "rent": Decimal("100.00")})

    def test_cli_budgets(self):
        import subprocess, sys
        out = subprocess.run([sys.executable, "-m", "ledger.cli", "data/entries-2026.csv", "--budgets", "data/budgets.csv"],
                             capture_output=True, text=True, check=True).stdout.splitlines()
        self.assertIn("TOTAL: 964.55", out)
        self.assertEqual(out[-2:], ["food: over by 4.75", "rent: over by 100.00"])
