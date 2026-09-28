import subprocess
import sys
import unittest


class ReportPackageAndCurrencies(unittest.TestCase):
    def test_old_imports_still_work(self):
        from ledger.report import load_entries, total_by_category
        from ledger import total_by_category as reexported
        self.assertIs(reexported, total_by_category)

    def test_new_modules(self):
        from ledger.report.loading import load_entries
        from ledger.report.aggregate import total_by_category
        self.assertEqual(total_by_category(load_entries("data/entries-2026.csv"))["transport"], 4180)

    def test_converts_to_eur_cents_rounding_half_up(self):
        from ledger.report import load_entries, total_by_category
        totals = total_by_category(load_entries("data/entries-multi.csv"))
        self.assertEqual(totals, {"food": 1420, "rent": 10000, "travel": 2340, "misc": 59})

    def cli(self, *args):
        return subprocess.run([sys.executable, "-m", "ledger.cli", *args],
                              capture_output=True, text=True, check=True).stdout.splitlines()

    def test_cli_default_is_eur(self):
        self.assertEqual(self.cli("data/entries-multi.csv")[-1], "TOTAL: 138.19")

    def test_cli_by_currency(self):
        self.assertEqual([l.strip() for l in self.cli("data/entries-multi.csv", "--by-currency")],
                         ["EUR: 105.00", "GBP: 20.50", "USD: 10.00"])
