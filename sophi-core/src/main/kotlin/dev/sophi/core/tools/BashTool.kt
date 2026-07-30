package dev.sophi.core.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

private const val DEFAULT_TIMEOUT_SECONDS = 120L
private const val MAX_TIMEOUT_SECONDS = 300L
private const val MAX_OUTPUT_CHARS = 100_000

private val READ_ONLY_PREFIXES = listOf(
    "ls", "cat", "pwd", "echo", "head", "tail", "wc", "find",
    "git status", "git log", "git diff", "git show"
)
private val UNSAFE_SHELL_CHARS = charArrayOf(';', '&', '|', '>', '<', '`', '$')

private val HIGH_RISK_BASH_SUBSTRINGS = listOf(
    "rm -rf", "rm -fr", "sudo ", "dd if=", "mkfs", "git push --force", "git push -f",
    "> /dev/", ":(){ :|:& };:", "curl | sh", "curl | bash", "wget | sh", "wget | bash"
)
private val SCRATCH_PATH_PREFIXES = listOf("/tmp/", "/private/tmp/", "./scratch/", "scratch/")
private val SINGLE_FILE_RM = Regex("""^rm\s+(\S+)$""")

@Serializable
private data class BashArgs(val command: String, val timeoutSeconds: Long? = null)

class BashTool(private val root: Path = Paths.get("").toAbsolutePath()) : Tool {

    override val name = "bash"
    override val description = "Run a shell command in the working directory"
    override fun riskLevel(argumentsJson: String): RiskLevel {
        val command = runCatching { json.decodeFromString<BashArgs>(argumentsJson).command }
            .getOrNull() ?: return RiskLevel.DESTRUCTIVE
        val trimmed = command.trim()
        val looksReadOnly = READ_ONLY_PREFIXES.any { trimmed.startsWith(it) } &&
            command.none { it in UNSAFE_SHELL_CHARS }
        return if (looksReadOnly) RiskLevel.CAUTION else RiskLevel.DESTRUCTIVE
    }
    override fun ruleVerdict(argumentsJson: String): RuleVerdict {
        val command = runCatching { json.decodeFromString<BashArgs>(argumentsJson).command }
            .getOrNull() ?: return RuleVerdict.HIGH_RISK
        val trimmed = command.trim()
        if (HIGH_RISK_BASH_SUBSTRINGS.any { trimmed.contains(it) }) return RuleVerdict.HIGH_RISK
        SINGLE_FILE_RM.matchEntire(trimmed)?.let { match ->
            val target = match.groupValues[1]
            if (SCRATCH_PATH_PREFIXES.any { target.startsWith(it) }) return RuleVerdict.LOW_RISK
        }
        return RuleVerdict.UNKNOWN
    }
    override val parametersJson = """
        {"type":"object","properties":{"command":{"type":"string","description":"Shell command to run"},"timeoutSeconds":{"type":"integer","description":"Max seconds to allow (default 120, capped at 300)"}},"required":["command"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val args = json.decodeFromString<BashArgs>(argumentsJson)
        val timeoutSeconds = (args.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS).coerceAtMost(MAX_TIMEOUT_SECONDS)

        val process = ProcessBuilder("sh", "-c", args.command)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()

        val outputDeferred = async {
            val buffer = StringBuilder()
            var truncated = false
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (buffer.length < MAX_OUTPUT_CHARS) buffer.append(line).append('\n') else truncated = true
                }
            }
            if (truncated) buffer.append("... output truncated\n")
            buffer.toString()
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        val output = outputDeferred.await()

        if (!finished) {
            "$output\nError: command timed out after ${timeoutSeconds}s"
        } else {
            val exitCode = process.exitValue()
            if (exitCode != 0) "$output\nCommand exited with code $exitCode" else output
        }
    }
}
