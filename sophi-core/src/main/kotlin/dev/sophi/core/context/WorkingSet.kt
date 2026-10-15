package dev.sophi.core.context

import dev.sophi.ai.api.Message
import dev.sophi.ai.api.MessageRole
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class FileTouch(val path: String, val reads: Int, val writes: Int, val lastAction: String)
data class CommandRun(val command: String, val exitCode: Int)
data class ToolError(val tool: String, val message: String)

private val lenient = Json { ignoreUnknownKeys = true }
private val exitRe = Regex("Command exited with code (\\d+)\\s*$")
private const val LINE_MAX = 160

/** Path / pattern / query / command of a tool call, or null when the arguments don't parse or hold none. */
internal fun keyArg(toolName: String, argumentsJson: String): String? = runCatching {
    val o = lenient.parseToJsonElement(argumentsJson).jsonObject
    val keys = when (toolName) {
        "bash" -> listOf("command")
        "grep", "glob", "search_context" -> listOf("pattern", "query")
        else -> listOf("path")
    }
    keys.firstNotNullOfOrNull { o[it]?.jsonPrimitive?.contentOrNull }
}.getOrNull()

/** What a session actually did, derived from its tool rounds (pure; never stale, free on resume). */
data class WorkingSet(
    val files: List<FileTouch>,
    val searches: List<String>,
    val commands: List<CommandRun>,
    val errors: List<ToolError>
) {
    fun filesSection(): String = section(files.takeLast(30).reversed()) {
        "${it.path} — read ${it.reads}, written ${it.writes}, last: ${it.lastAction}"
    }

    fun commandsSection(): String = section(commands.takeLast(20)) { "${it.command} → exit ${it.exitCode}" }

    fun errorsSection(): String = section(errors.takeLast(10)) { "${it.tool}: ${it.message.lineSequence().first()}" }

    private fun <T> section(items: List<T>, line: (T) -> String): String =
        if (items.isEmpty()) "(none)" else items.joinToString("\n") { line(it).take(LINE_MAX) }

    companion object {
        fun fromEntries(entries: List<SessionEntry>): WorkingSet {
            val calls = entries.filter { it.role == EntryRole.ASSISTANT }.flatMap { e ->
                runCatching {
                    lenient.decodeFromString<List<JsonObject>>(e.metadata["toolCalls"] ?: "[]").map {
                        ToolCall(
                            it["id"]!!.jsonPrimitive.content,
                            it["name"]!!.jsonPrimitive.content,
                            it["argumentsJson"]!!.jsonPrimitive.content
                        )
                    }
                }.getOrDefault(emptyList())
            }
            val results = entries.filter { it.role == EntryRole.TOOL_RESULT }
                .mapNotNull { e -> e.metadata["toolCallId"]?.let { it to e.content } }.toMap()
            return build(calls, results)
        }

        fun fromMessages(messages: List<Message>): WorkingSet {
            val calls = messages.filter { it.role == MessageRole.ASSISTANT }.flatMap { it.toolCalls.orEmpty() }
            val results = messages.filter { it.role == MessageRole.TOOL }
                .mapNotNull { m -> m.toolCallId?.let { it to m.content } }.toMap()
            return build(calls, results)
        }

        private fun build(calls: List<ToolCall>, results: Map<String, String>): WorkingSet {
            // LinkedHashMap, re-inserted on every touch: iteration order = least to most recently touched.
            val files = LinkedHashMap<String, FileTouch>()
            val searches = mutableListOf<String>()
            val commands = mutableListOf<CommandRun>()
            val errors = mutableListOf<ToolError>()
            for (c in calls) {
                val result = results[c.id]
                if (result != null && result.startsWith("Error: ")) errors += ToolError(c.name, result)
                val arg = keyArg(c.name, c.argumentsJson) ?: continue
                when (c.name) {
                    "read_file", "read_context", "write_file", "edit_file" -> {
                        val action = when (c.name) { "write_file" -> "write"; "edit_file" -> "edit"; else -> "read" }
                        val old = files.remove(arg) ?: FileTouch(arg, 0, 0, action)
                        files[arg] = if (action == "read") old.copy(reads = old.reads + 1, lastAction = action)
                        else old.copy(writes = old.writes + 1, lastAction = action)
                    }
                    "grep", "glob", "search_context" -> searches += arg
                    "bash" -> commands += CommandRun(arg, result?.let { exitRe.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0)
                }
            }
            return WorkingSet(files.values.toList(), searches, commands, errors)
        }
    }
}
