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
