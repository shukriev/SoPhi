import unittest
from ledger.report import total_by_category


class ReportTest(unittest.TestCase):
    def test_totals(self):
        entries = [("food", 1250), ("food", 725), ("rent", 90000)]
        self.assertEqual(total_by_category(entries), {"food": 1975, "rent": 90000})
