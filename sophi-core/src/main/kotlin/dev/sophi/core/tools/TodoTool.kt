package dev.sophi.core.tools

import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class TodoItem(val text: String, val status: String)

@Serializable
private data class TodoArgs(val items: List<TodoItem>)

private const val MAX_ITEMS = 30
private const val MAX_TEXT = 200
private const val CLEARED = "(todo list cleared)"
private val MARKS = mapOf("done" to "[x] ", "in_progress" to "[>] ", "pending" to "[ ] ")
private val todoJson = Json { ignoreUnknownKeys = true }

fun renderTodos(items: List<TodoItem>): String =
    if (items.isEmpty()) CLEARED else items.joinToString("\n") { MARKS.getValue(it.status) + it.text }

private fun parseRendered(text: String): List<TodoItem>? {
    // the loop may append a note after a blank line (e.g. a repeated identical call); the list ends there
    val list = text.substringBefore("\n\n")
    if (list == CLEARED) return emptyList()
    return list.lines().map { line ->
        val status = MARKS.entries.firstOrNull { line.startsWith(it.value) }?.key ?: return null
        TodoItem(line.substring(4), status)
    }
}

/**
 * The session's todo list: the newest `todo` result on the branch, or what compaction carried over (the summary's
 * `_todo`); null when the model never kept one. The list lives in the session, so resume and /checkout bring it back.
 */
fun currentTodos(session: AgentSession): List<TodoItem>? = currentTodos(session.branch())

/** [currentTodos] over [entries] in order: the newest readable `todo` result, else the first entry's `_todo`. */
fun currentTodos(entries: List<SessionEntry>): List<TodoItem>? =
    entries.asReversed().asSequence()
        .filter { it.role == EntryRole.TOOL_RESULT && it.metadata["toolName"] == "todo" }
        .mapNotNull { parseRendered(it.content) }
        .firstOrNull()
        ?: entries.firstOrNull()?.metadata?.get("_todo")?.let { runCatching { todoJson.decodeFromString<List<TodoItem>>(it) }.getOrNull() }

/** A session checklist the model keeps by replacing the whole list each call. Survives compaction (see ContextCompactor). */
class TodoTool : Tool {
    override val name = "todo"
    override val description = "Keep a checklist for a task with 3 or more steps: send the whole list each time " +
        "(it replaces the old one), mark the step you're on in_progress and finished ones done. An empty list clears it."
    override val parametersJson = """
        {"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"text":{"type":"string"},"status":{"type":"string","enum":["pending","in_progress","done"]}},"required":["text","status"]}}},"required":["items"]}
    """.trimIndent()

    override suspend fun execute(argumentsJson: String): String {
        val items = runCatching { todoJson.decodeFromString<TodoArgs>(argumentsJson).items }
            .getOrElse { return "Error: arguments must be {\"items\": [{\"text\": ..., \"status\": \"pending\"|\"in_progress\"|\"done\"}]}" }
        if (items.size > MAX_ITEMS) return "Error: at most $MAX_ITEMS items"
        items.firstOrNull { it.status !in MARKS }?.let { return "Error: status is pending, in_progress or done, got \"${it.status}\"" }
        items.firstOrNull { it.text.length > MAX_TEXT || it.text.isBlank() || '\n' in it.text }
            ?.let { return "Error: each item is one line of 1-$MAX_TEXT characters" }
        if (items.count { it.status == "in_progress" } > 1) return "Error: only one item can be in_progress"
        return renderTodos(items)
    }
}
