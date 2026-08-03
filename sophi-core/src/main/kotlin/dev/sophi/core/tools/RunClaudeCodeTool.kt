package dev.sophi.core.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
private data class RunClaudeCodeArgs(
    @SerialName("project_path") val projectPath: String,
    val task: String,
    @SerialName("permission_mode") val permissionMode: String = "auto",
    @SerialName("allowed_tools") val allowedTools: List<String> = emptyList(),
    @SerialName("timeout_seconds") val timeoutSeconds: Long = 1800
)

/**
 * Hands a task to a headless Claude Code session (`claude -p ...`) in a target project
 * directory. riskLevel/ruleVerdict are both hardcoded, never argument-dependent — this spawns
 * an entire autonomous coding agent, so it must never be silently auto-approved by a risk
 * classifier. The only way it runs unattended is an explicit toolGrants entry a human sets up
 * once (ADR-016's "ask once, not per call"), e.g. when creating a scheduled Goal-mode task.
 */
class RunClaudeCodeTool(
    private val claudeCommand: List<String> = listOf("claude")
) : Tool {
    override val name = "invoke_claude_code"
    override val description = "Hands a well-scoped task to a headless Claude Code session " +
        "in a target project directory, to plan and implement it. Use for a ticket whose " +
        "scope is already clear (e.g. a Trello card's own description) — this does not ask " +
        "clarifying questions back, since nothing is listening for them."
    override val parametersJson = """
        {"type":"object","properties":{
          "project_path":{"type":"string","description":"Absolute path to the target repo/worktree"},
          "task":{"type":"string","description":"The task description to hand to Claude Code, verbatim"},
          "permission_mode":{"type":"string","description":"Claude Code --permission-mode value (default \"auto\")"},
          "allowed_tools":{"type":"array","items":{"type":"string"},"description":"Optional extra scoping via --allowedTools"},
          "timeout_seconds":{"type":"integer","description":"Hard wall-clock cap — no one is watching an unattended run"}
        },"required":["project_path","task"]}
    """.trimIndent()

    override fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.DESTRUCTIVE
    override fun ruleVerdict(argumentsJson: String): RuleVerdict = RuleVerdict.HIGH_RISK

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val args = json.decodeFromString(RunClaudeCodeArgs.serializer(), argumentsJson)
        val command = claudeCommand + buildList {
            add("-p"); add(args.task)
            add("--permission-mode"); add(args.permissionMode)
            add("--output-format"); add("json")
            if (args.allowedTools.isNotEmpty()) {
                add("--allowedTools"); addAll(args.allowedTools)
            }
        }
        val process = ProcessBuilder(command)
            .directory(File(args.projectPath))
            .redirectErrorStream(true)
            .start()

        // Must read concurrently with waitFor(), not after — sequential read-after-wait
        // deadlocks once output exceeds the OS pipe buffer (same reason BashTool reads this
        // way).
        val outputDeferred = async { process.inputStream.bufferedReader().readText() }

        val finished = process.waitFor(args.timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        val output = outputDeferred.await()

        when {
            !finished -> "Error: claude code invocation timed out after ${args.timeoutSeconds}s"
            process.exitValue() != 0 -> "Error: claude exited ${process.exitValue()}: $output"
            else -> output
        }
    }
}
