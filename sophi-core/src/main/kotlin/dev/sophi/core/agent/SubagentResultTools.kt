package dev.sophi.core.agent

import dev.sophi.core.agent.BackgroundSubagents.Status
import dev.sophi.core.session.SessionIdContext
import dev.sophi.core.tools.Tool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

@Serializable
private data class SubagentIdArgs(val id: String, @SerialName("wait_seconds") val waitSeconds: Int = 0)

private val idJson = Json { ignoreUnknownKeys = true }

private suspend fun callerSession() = coroutineContext[SessionIdContext]?.sessionId
    ?: error("no SessionIdContext — the host must wrap turns in it")

/** `running` / `done` / `failed: <reason>` / `cancelled`. */
val BackgroundSubagents.Info.statusLine: String get() = when (status) {
    Status.RUNNING -> "running"
    Status.DONE -> "done"
    Status.FAILED -> "failed: $text"
    Status.CANCELLED -> "cancelled"
}

class SubagentResultTool(private val subagents: BackgroundSubagents) : Tool {
    override val name = "subagent_result"
    override val description = "Read a background subagent's status and, once it is done, its answer. " +
        "wait_seconds (up to 120) waits for it to finish, instead of polling."
    override val parametersJson = """
        {"type":"object","properties":{"id":{"type":"string","description":"Subagent id from delegate_to_subagent(background=true), e.g. b1"},"wait_seconds":{"type":"integer","description":"Wait up to this many seconds (max 120) for it to finish; default 0"}},"required":["id"]}
    """.trimIndent()
    override val deferred = true
    override suspend fun execute(argumentsJson: String): String {
        val args = idJson.decodeFromString<SubagentIdArgs>(argumentsJson)
        val info = subagents.result(callerSession(), args.id, args.waitSeconds) ?: return "Error: no such subagent ${args.id}"
        return if (info.status == Status.DONE) "done\n${info.text}" else info.statusLine
    }
}

class SubagentCancelTool(private val subagents: BackgroundSubagents) : Tool {
    override val name = "subagent_cancel"
    override val description = "Stop a background subagent. What it did so far stays in its session, which you can resume."
    override val parametersJson = """
        {"type":"object","properties":{"id":{"type":"string","description":"Subagent id from delegate_to_subagent(background=true), e.g. b1"}},"required":["id"]}
    """.trimIndent()
    override val deferred = true
    override suspend fun execute(argumentsJson: String): String {
        val id = idJson.decodeFromString<SubagentIdArgs>(argumentsJson).id
        val info = subagents.cancel(callerSession(), id) ?: return "Error: no such subagent $id"
        return if (info.status == Status.CANCELLED) "cancelled $id" else info.statusLine
    }
}
