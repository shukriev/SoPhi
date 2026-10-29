package dev.sophi.core.tools

import dev.sophi.core.permissions.PermissionSubject
import dev.sophi.core.permissions.splitBashCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
/** find actions that delete, run a command or write a file: not read-only. */
private val FIND_WRITES = Regex("""\s-(delete|exec|execdir|ok|okdir|fprint\w*|fls)\b""")

private val HIGH_RISK_BASH_SUBSTRINGS = listOf(
    "rm -rf", "rm -fr", "sudo ", "dd if=", "mkfs", "git push --force", "git push -f",
    "> /dev/", ":(){ :|:& };:", "curl | sh", "curl | bash", "wget | sh", "wget | bash"
)
private val SCRATCH_PATH_PREFIXES = listOf("/tmp/", "/private/tmp/", "./scratch/", "scratch/")
private val SINGLE_FILE_RM = Regex("""^rm\s+(\S+)$""")

@Serializable
private data class BashArgs(val command: String, val timeoutSeconds: Long? = null)

/** [sandbox]: where each command runs; a host can switch it while the session runs. */
class BashTool(
    private val root: Path = Paths.get("").toAbsolutePath(),
    private val sandbox: BashSandbox? = null,
) : Tool {

    override val name = "bash"
    override val description = "Run a shell command in the working directory. To delete, move or rename files use delete_file / move_file instead: those can be undone."
    override fun riskLevel(argumentsJson: String): RiskLevel {
        val command = runCatching { json.decodeFromString<BashArgs>(argumentsJson).command }
            .getOrNull() ?: return RiskLevel.DESTRUCTIVE
        val trimmed = command.trim()
        val looksReadOnly = READ_ONLY_PREFIXES.any { trimmed.startsWith(it) } &&
            command.none { it in UNSAFE_SHELL_CHARS } &&
            !(trimmed.startsWith("find") && FIND_WRITES.containsMatchIn(trimmed))
        // Reading outside the workspace is not "read-only" in the sense that matters: `cat ~/.ssh/id_rsa`.
        val staysInside = bashReachesOutside(command, root) == null
        return if (looksReadOnly && staysInside) RiskLevel.CAUTION else RiskLevel.DESTRUCTIVE
    }
    override fun ruleVerdict(argumentsJson: String): RuleVerdict {
        // Unparseable: execute() decodes the same type and throws before doing anything, so don't prompt —
        // let it fail with the parse error the model can act on. (riskLevel still fails closed; ADR-027.)
        val command = runCatching { json.decodeFromString<BashArgs>(argumentsJson).command }
            .getOrNull() ?: return RuleVerdict.LOW_RISK
        val trimmed = command.trim()
        if (HIGH_RISK_BASH_SUBSTRINGS.any { trimmed.contains(it) }) return RuleVerdict.HIGH_RISK
        SINGLE_FILE_RM.matchEntire(trimmed)?.let { match ->
            val target = match.groupValues[1]
            if (SCRATCH_PATH_PREFIXES.any { target.startsWith(it) }) return RuleVerdict.LOW_RISK
        }
        return RuleVerdict.UNKNOWN
    }
    override fun permissionSubjects(argumentsJson: String): List<PermissionSubject>? =
        runCatching { json.decodeFromString<BashArgs>(argumentsJson).command }.getOrNull()
            ?.let { splitBashCommand(it) }
            // nothing to match (empty command, bare assignment) is null, never an empty list: that means "no subjects"
            ?.takeIf { it.isNotEmpty() }
            ?.map { PermissionSubject(it.text, allowable = !it.redirects && !it.assigns) }
    override val parametersJson = """
        {"type":"object","properties":{"command":{"type":"string","description":"Shell command to run"},"timeoutSeconds":{"type":"integer","description":"Max seconds to allow (default 120, capped at 300)"}},"required":["command"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val args = json.decodeFromString<BashArgs>(argumentsJson)
        val timeoutSeconds = (args.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS).coerceAtMost(MAX_TIMEOUT_SECONDS)
        val builder = shellProcess(args.command).directory(root.toFile()).redirectErrorStream(true)
        // held until the command ends, so a sandbox switch mid-command doesn't pull its container away
        val lease = sandbox?.lease(builder.command())
        try {
            run(builder.apply { lease?.let { command(it.command) } }, timeoutSeconds)
        } finally {
            lease?.release()
        }
    }

    private suspend fun run(builder: ProcessBuilder, timeoutSeconds: Long): String = coroutineScope {
        val process = builder.start()
        // No input is ever fed to a command; an open pipe left one that reads stdin blocking until the timeout.
        process.outputStream.close()

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
            "Error: command timed out after ${timeoutSeconds}s\n$output"
        } else {
            val exitCode = process.exitValue()
            if (exitCode != 0) "$output\nCommand exited with code $exitCode" else output
        }
    }
}
