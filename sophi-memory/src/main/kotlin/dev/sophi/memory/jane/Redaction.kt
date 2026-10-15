package dev.sophi.memory.jane

private val patterns = listOf(
    // Card-like: 13-19 digits allowing space/dash groups.
    Regex("""\b(?:\d[ -]?){13,19}\b"""),
    // Long bare digit runs (government IDs, account numbers): 7+ digits.
    Regex("""\b\d{7,}\b"""),
    // Secret-keyword assignments in English, Bulgarian and German: the keyword, up to 40 characters of the same
    // sentence ("for the wifi", "на картата ми", "für das WLAN"), a connector, then the value. "Password manager"
    // is a fact, not a credential. (?U): Cyrillic word boundaries.
    // ponytail: keyword-before-value only; add the reverse order ("lipa2024 is my password") if it shows up.
    Regex(
        """(?iU)\b(password|passcode|passphrase|pin|api[_ ]?key|secret|token|паролата|парола|пин|passwort|kennwort|geheimzahl)\b""" +
            """(?![\s-]*(manager|мениджър|менажер))[^.!?\n]{0,40}?\b(is|are|was|е|са|ist|lautet)\b\s*\S+""" +
            """|(?iU)\b(password|passcode|api[_ ]?key|secret|token)\b\s*[:=]\s*\S+"""
    )
)

/**
 * Backstop redaction before any memory text is written (spec §7): the encoder prompt
 * already excludes credentials/IDs, this catches what slips through.
 */
fun redact(text: String): String =
    patterns.fold(text) { acc, re -> re.replace(acc, "[REDACTED]") }
