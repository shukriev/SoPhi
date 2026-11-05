package dev.sophi.core.tools

import dev.sophi.core.audit.ActorContext
import dev.sophi.core.audit.LOCAL_ACTOR
import dev.sophi.core.credentials.SecretMask
import dev.sophi.core.session.SessionHit
import dev.sophi.core.session.SessionIdContext
import dev.sophi.core.session.SessionManager
import dev.sophi.core.session.searchSessions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

@Serializable
private data class SessionSearchArgs(val query: String = "", val limit: Int = 8)

private val searchJson = Json { ignoreUnknownKeys = true }

/** One line per hit, then its snippet: what the tool returns and `/search` prints. */
fun formatSessionHits(hits: List<SessionHit>): String = hits.joinToString("\n") { h ->
    val date = Instant.ofEpochMilli(h.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    "$date  ${h.sessionId}  ${h.title ?: "(untitled)"}  (${h.matches} match${if (h.matches == 1) "" else "es"})\n  ${h.snippet}"
}

/** Full-text search over past sessions (not the one running), newest first. Results are redacted (see [searchSessions]). */
class SessionSearchTool(private val sessions: SessionManager, private val mask: SecretMask? = null) : Tool {
    override val name = "session_search"
    override val description = "Search past chat sessions for what was said or done, e.g. an earlier decision. " +
        "Every word of the query must appear in one message (any case). Returns the newest matching sessions with id, title, date and a snippet. " +
        "Notes or conventions the user wrote down for you are skills: check the `skill` tool first."
    override val parametersJson = """
        {"type":"object","properties":{"query":{"type":"string","description":"words that must all appear"},"limit":{"type":"integer","description":"most sessions to return, 1-20 (default 8)"}},"required":["query"]}
    """.trimIndent()

    override suspend fun execute(argumentsJson: String): String {
        // Sessions don't record whose they are, so only the local user may search them.
        val actor = currentCoroutineContext()[ActorContext]?.actorId
        if (actor != null && actor != LOCAL_ACTOR) return "Error: session search is only available to the local user"
        val args = runCatching { searchJson.decodeFromString<SessionSearchArgs>(argumentsJson) }
            .getOrElse { return "Error: arguments must be {\"query\": \"words\", \"limit\": 8}" }
        if (args.query.isBlank()) return "Error: query is empty"
        val skip = currentCoroutineContext()[SessionIdContext]?.sessionId
        val hits = searchSessions(sessions, args.query, args.limit.coerceIn(1, 20), skip, mask)
        return if (hits.isEmpty()) "No past sessions mention: ${args.query.trim()}. Notes the user wrote down for you may be a skill (`skill` tool)." else formatSessionHits(hits)
    }
}
