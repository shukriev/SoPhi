import unittest


class ReportPackage(unittest.TestCase):
    def test_old_imports_still_work(self):
        from ledger.report import load_entries, total_by_category
        from ledger import total_by_category as reexported
        self.assertIs(reexported, total_by_category)

    def test_new_modules(self):
        from ledger.report.loading import load_entries
        from ledger.report.aggregate import total_by_category
        self.assertEqual(total_by_category(load_entries("data/entries-2026.csv"))["transport"], 4180)
