package dev.sophi.skills

import java.nio.file.Path

/** One thing the install scan didn't like: which file (relative to the skill folder) and why. */
data class ScanFinding(val file: String, val reason: String)

/**
 * The install scan's rules: a pattern and the reason shown to the user. Heuristic, not a
 * guarantee: a determined author can phrase around any of these. It catches the common shapes.
 */
private val SCAN_RULES = listOf(
    Regex("""(?i)\b(curl|wget)\b[^\n|]*\|\s*(sudo\s+)?(ba|z)?sh\b""") to "pipes a download straight into a shell",
    Regex("""(?i)(\.ssh/id_|\.aws/credentials|\.netrc|security\s+find-(generic|internet)-password|\bkeychain\b)""") to "reads a secret store (ssh keys, cloud credentials, keychain)",
    Regex("""(?i)\b(printenv|env)\b[^\n]*\|\s*(curl|wget|nc)\b|\b(curl|wget)\b[^\n]*\$\{?[A-Z_]*(KEY|TOKEN|SECRET|PASSWORD)""") to "sends environment variables or secrets over the network",
    Regex("""[A-Za-z0-9+/]{200,}={0,2}""") to "contains a long base64 blob",
    Regex("""[​-‏‪-‮⁠-⁤⁦-⁩﻿\x{E0000}-\x{E007F}]""") to "contains hidden Unicode (zero-width or bidi control characters)",
    Regex("""(?i)ignore (all )?(previous|prior|above) instructions|disregard (your|the) system prompt""") to "contains a prompt-injection phrase",
)

/** Static scan of every file in a skill folder, run before an install is accepted. */
fun scanSkill(skillDir: Path): List<ScanFinding> =
    skillDir.toFile().walkTopDown().filter { it.isFile }.sortedBy { it.path }.flatMap { file ->
        val rel = skillDir.relativize(file.toPath()).toString()
        val text = runCatching { file.readText() }.getOrDefault("")
        SCAN_RULES.filter { (rx, _) -> rx.containsMatchIn(text) }.map { ScanFinding(rel, it.second) } +
            listOfNotNull(ScanFinding(rel, "is an executable file").takeIf { file.canExecute() })
    }.toList()
