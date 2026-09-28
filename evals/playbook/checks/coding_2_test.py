import unittest
from ledger.report import average_by_category


class Average(unittest.TestCase):
    def test_average(self):
        self.assertEqual(average_by_category([("a", 100), ("a", 201), ("b", 5)]), {"a": 151, "b": 5})

    def test_rounds_half_up(self):
        self.assertEqual(average_by_category([("a", 1), ("a", 2)]), {"a": 2})
