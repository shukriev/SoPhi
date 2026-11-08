package dev.sophi.core.tools

import dev.sophi.core.permissions.PermissionSubject
import dev.sophi.core.permissions.pathSubject
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

// A shell word with nothing the shell expands: plain text, '...' (literal), or "..." without $ ` \ !.
private const val LITERAL_WORD = """(?:[^\s'"\\${'$'}`;&|<>(){}\[\]*?~#!]+|'[^']*'|"[^"\\${'$'}`!]*")+"""
private const val PLAIN_PATH = """([A-Za-z0-9._/+@%=:,-]+)"""
private val ECHO_WRITE = Regex("""(?:echo|printf)(?:\s+$LITERAL_WORD)+\s+>>?\s*$PLAIN_PATH""")
private val HEREDOC_WRITE = Regex("""cat\s*(?:>>?\s*$PLAIN_PATH\s*<<\s*(['"]?)(\w+)\2|<<\s*(['"]?)(\w+)\4\s*>>?\s*$PLAIN_PATH)""")

/**
 * The path a command writes when writing that one file is all it does: `echo`/`printf` of literal words, or a `cat`
 * heredoc whose body is literal (quoted delimiter, or no `$`, backtick or backslash in it), into a plain path with `>`/`>>`.
 * Anything else (pipes, `;`/`&&`, substitutions, variables, a second redirect, text after the heredoc) is null.
 */
private fun shellFileWriteTarget(command: String): String? {
    val nl = command.indexOf('\n')
    if (nl < 0) return ECHO_WRITE.matchEntire(command.trim())?.groupValues?.get(1)
    val g = HEREDOC_WRITE.matchEntire(command.substring(0, nl).trim())?.groupValues ?: return null
    val (target, quote, delim) = if (g[1].isNotEmpty()) Triple(g[1], g[2], g[3]) else Triple(g[6], g[4], g[5])
    val body = command.substring(nl + 1).trimEnd().lines()
    if (body.last() != delim || body.dropLast(1).any { it == delim }) return null
    if (quote.isEmpty() && body.any { line -> line.any { it in "$`\\" } }) return null
    return target
}

@Serializable
private data class BashArgs(val command: String, val timeoutSeconds: Long? = null, val background: Boolean = false)

/** [sandbox]: where each command runs; a host can switch it while the session runs. [jobs]: null = no background jobs. */
class BashTool(
    private val root: Path = Paths.get("").toAbsolutePath(),
    private val sandbox: BashSandbox? = null,
    private val jobs: BashJobs? = null,
) : Tool {

    override val name = "bash"
    override val description = "Run a shell command in the working directory. To delete, move or rename files use delete_file / move_file instead: those can be undone. Every command starts at the workspace root; a cd does not persist between calls, so use paths relative to the root."
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
    /** Always a process; writes unless [riskLevel] found it read-only and inside; network unless a sandbox turns it off. */
    override fun effects(argumentsJson: String): Set<EffectKind> = buildSet {
        add(EffectKind.PROCESS)
        if (riskLevel(argumentsJson) != RiskLevel.CAUTION) add(EffectKind.WRITES)
        if (sandbox == null || sandbox.mode == SandboxMode.OFF || sandbox.network) add(EffectKind.NETWORK)
    }
    override fun ruleVerdict(argumentsJson: String): RuleVerdict {
        // Unparseable: execute() decodes the same type and throws before doing anything, so don't prompt —
        // let it fail with the parse error the model can act on. (riskLevel still fails closed; ADR-027.)
        val command = runCatching { json.decodeFromString<BashArgs>(argumentsJson).command }
            .getOrNull() ?: return RuleVerdict.LOW_RISK
        val trimmed = command.trim()
        if (HIGH_RISK_BASH_SUBSTRINGS.any { trimmed.contains(it) }) return RuleVerdict.HIGH_RISK
        shellFileWriteTarget(command)?.let { return classifyPathRisk(root, it) }
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
    /**
     * write_file's permission subject for a command whose only effect is writing one file inside the workspace
     * ([shellFileWriteTarget]), so write_file rules cover it too; null for any other command. Undo needs nothing more:
     * such a command rates DESTRUCTIVE, so its round is checkpointed like a write_file round.
     */
    fun fileWriteSubject(argumentsJson: String): PermissionSubject? {
        val command = runCatching { json.decodeFromString<BashArgs>(argumentsJson).command }.getOrNull() ?: return null
        val target = shellFileWriteTarget(command) ?: return null
        // outside the workspace (`..`, an absolute path, a symlink out) pathSubject spells it absolute
        return pathSubject(root, root.resolve(target).normalize())?.takeUnless { it.text.startsWith("/") }
    }
    override val parametersJson = """
        {"type":"object","properties":{"command":{"type":"string","description":"Shell command to run"},"timeoutSeconds":{"type":"integer","description":"Max seconds to allow (default 120, capped at 300)"},"background":{"type":"boolean","description":"Start a long-running command (dev server, watcher, long build) and return at once with a job id; read it with bash_output, stop it with bash_kill"}},"required":["command"]}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val args = json.decodeFromString<BashArgs>(argumentsJson)
        if (args.background) return@withContext background(args.command)
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

    private suspend fun background(command: String): String {
        val jobs = jobs ?: return "Error: background jobs aren't available here"
        if (sandbox?.mode == SandboxMode.DOCKER) return "Error: background jobs don't run in the Docker sandbox yet: killing one " +
            "wouldn't stop it inside the container. Switch /settings sandbox to seatbelt or off, or run it in the foreground."
        val builder = shellProcess(command).directory(root.toFile())
        val lease = sandbox?.lease(builder.command())
        lease?.let { builder.command(it.command) }
        val session = kotlin.coroutines.coroutineContext[dev.sophi.core.session.SessionIdContext]?.sessionId ?: "default"
        val started = try {
            jobs.start(session, command, builder) { lease?.release() }
        } catch (e: IllegalStateException) {
            lease?.release()
            return "Error: ${e.message}"
        }
        return "job ${started.id} started\n${started.initialOutput}\n" +
            "Read it with bash_output(id), stop it with bash_kill(id) (load them with tool_search)."
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
                    if (buffer.length < MAX_RESULT_CHARS) buffer.append(line).append('\n') else truncated = true
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
