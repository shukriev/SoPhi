import unittest
from ledger.money import parse_amount, format_amount


class MoneyTest(unittest.TestCase):
    def test_parse(self):
        self.assertEqual(parse_amount("12.50"), 1250)
        self.assertEqual(parse_amount("7.5"), 750)
        self.assertEqual(parse_amount("-3.20"), -320)
        self.assertEqual(parse_amount("3"), 300)

    def test_format(self):
        self.assertEqual(format_amount(1250), "12.50")
        self.assertEqual(format_amount(-5), "-0.05")
        self.assertEqual(format_amount(900), "9.00")
