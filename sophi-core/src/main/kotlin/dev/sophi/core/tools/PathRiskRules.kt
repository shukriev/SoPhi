package dev.sophi.core.tools

import java.nio.file.Path

private val SENSITIVE_PATH_MARKERS = listOf(".env", "credentials", "secret", ".git/")
private val SCRATCH_PATH_PREFIXES = listOf("scratch/", "tmp/", "/tmp/")

/**
 * Auto-mode rule heuristic shared by tools that write to a path within [root]: escaping root is
 * always HIGH_RISK, a path naming something sensitive is HIGH_RISK, a path under a scratch/temp
 * prefix is LOW_RISK, everything else is UNKNOWN (fall back to the LLM classifier).
 */
internal fun classifyPathRisk(root: Path, rawPath: String): RuleVerdict {
    val resolved = runCatching { root.resolve(rawPath).normalize() }.getOrNull()
        ?: return RuleVerdict.HIGH_RISK
    if (!resolved.startsWith(root)) return RuleVerdict.HIGH_RISK
    val normalized = rawPath.replace('\\', '/')
    if (SENSITIVE_PATH_MARKERS.any { normalized.contains(it) }) return RuleVerdict.HIGH_RISK
    if (SCRATCH_PATH_PREFIXES.any { normalized.startsWith(it) }) return RuleVerdict.LOW_RISK
    return RuleVerdict.UNKNOWN
}

private val SOURCE_EXTENSIONS = setOf(
    "py", "kt", "kts", "java", "scala", "ts", "tsx", "js", "jsx", "go", "rs", "rb", "swift",
    "c", "cc", "cpp", "h", "hpp", "cs", "php", "sh"
)

/**
 * Appended to a successful edit/write of a source file. Right after this result is where a model
 * decides it's done, and without the reminder it tends to check by running the program once instead
 * of the tests (playbook coding-3: every failing run edited, ran the CLI, and stopped).
 */
internal fun withTestHint(result: String, rawPath: String): String =
    if (rawPath.substringAfterLast('.', "").lowercase() in SOURCE_EXTENSIONS)
        "$result — code changed: run the project's tests before reporting done."
    else result
