package dev.sophi.sdk

private val SECRET_PATTERNS = listOf(
    Regex("""AKIA[0-9A-Z]{16}"""),
    Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
    Regex("""(?i)(api[_-]?key|secret|token|password)\s*[:=]\s*['"][A-Za-z0-9\-_.]{12,}['"]""")
)

private val PROMPT_INJECTION_PATTERNS = listOf(
    Regex("(?i)ignore (all )?previous instructions"),
    Regex("(?i)disregard (your|the) system prompt"),
    Regex("(?i)you are now (in )?(developer|debug|jailbreak) mode")
)

private const val MAX_SKILL_CONTENT_LENGTH = 50_000

/** Static checks every write_skill call runs before a write lands — self-authored content only. */
fun checkSkillContent(content: String): List<String> = buildList {
    if (content.length > MAX_SKILL_CONTENT_LENGTH) add("content exceeds $MAX_SKILL_CONTENT_LENGTH characters")
    SECRET_PATTERNS.forEach { pattern -> if (pattern.containsMatchIn(content)) add("content matches a secret/credential pattern (${pattern.pattern})") }
}

/**
 * Superset of [checkSkillContent] for install_skill — third-party content also gets scanned for
 * common prompt-injection phrasing. Heuristic, not a guarantee: a real attacker can phrase around
 * this list, but it catches the most common shape cheaply.
 */
fun checkInstalledSkillContent(content: String): List<String> = checkSkillContent(content) + buildList {
    PROMPT_INJECTION_PATTERNS.forEach { pattern -> if (pattern.containsMatchIn(content)) add("content matches a prompt-injection-shaped phrase (${pattern.pattern})") }
}
