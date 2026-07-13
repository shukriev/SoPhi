package dev.sophi.memory.jane

private val patterns = listOf(
    // Card-like: 13-19 digits allowing space/dash groups.
    Regex("""\b(?:\d[ -]?){13,19}\b"""),
    // Long bare digit runs (government IDs, account numbers): 7+ digits.
    Regex("""\b\d{7,}\b"""),
    // Secret-keyword assignments: password/api key/token/secret followed by a value.
    Regex("""(?i)\b(password|passcode|api[_ ]?key|secret|token)\b\s*(is|:|=)\s*\S+""")
)

/**
 * Backstop redaction before any memory text is written (spec §7): the encoder prompt
 * already excludes credentials/IDs, this catches what slips through.
 */
fun redact(text: String): String =
    patterns.fold(text) { acc, re -> re.replace(acc, "[REDACTED]") }
