def parse_amount(text):
    """'12.50' -> 1250 cents. Accepts a leading '-' and a missing or one-digit fraction."""
    text = text.strip()
    sign = -1 if text.startswith("-") else 1
    whole, _, frac = text.lstrip("-").partition(".")
    frac = (frac + "00")[:2]
    return sign * (int(whole or "0") * 100 + int(frac))


def format_amount(cents):
    """1250 -> '12.50', -5 -> '-0.05'."""
    sign = "-" if cents < 0 else ""
    cents = abs(cents)
    return f"{sign}{cents // 100}.{cents % 100:02d}"
