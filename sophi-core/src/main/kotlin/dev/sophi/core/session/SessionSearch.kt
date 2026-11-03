package dev.sophi.core.session

import dev.sophi.core.audit.redactWindowed
import dev.sophi.core.credentials.SecretMask

/** One past session that mentions every search term: its newest matching entry, and how many entries matched. */
data class SessionHit(
    val sessionId: String,
    val title: String?,
    val timestamp: Long,
    val matches: Int,
    val snippet: String,
)

private const val SNIPPET_AROUND = 120
// The same window the audit log redacts on: far longer than any secret pattern match.
private const val REDACT_WINDOW = 20_000

/**
 * Past sessions in which one entry (not a system prompt) holds every whitespace-separated term of [query], any case;
 * newest session first, at most [limit], [skip] (the current session) left out. Entries are redacted (secret patterns,
 * then [mask]) before they are matched, so a secret is neither shown nor findable. A file that won't load is skipped.
 */
// ponytail: reads every session file on each call (~7 MB / 430 sessions locally); add an index if a search gets slow.
fun searchSessions(manager: SessionManager, query: String, limit: Int = 10, skip: String? = null, mask: SecretMask? = null): List<SessionHit> {
    val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct()
    if (terms.isEmpty()) return emptyList()
    return manager.list().asSequence()
        .filter { it.id != skip }
        .sortedByDescending { it.lastModifiedMillis }
        .mapNotNull { meta ->
            val session = runCatching { manager.load(meta.id) }.getOrNull() ?: return@mapNotNull null
            fun matches(text: String) = text.lowercase().let { lower -> terms.all { it in lower } }
            // Raw text first, so only candidates pay for redaction; then again, so a term found only inside a secret misses.
            val found = session.entries.filter { it.role != EntryRole.SYSTEM && matches(it.content) }
                .map { e -> e to redactWindowed(e.content, REDACT_WINDOW).let { mask?.mask(it) ?: it } }
                .filter { (_, text) -> matches(text) }
            val (newest, text) = found.maxByOrNull { it.first.timestamp } ?: return@mapNotNull null
            SessionHit(meta.id, session.title ?: meta.title, newest.timestamp, found.size, snippet(text, terms[0]))
        }
        .take(limit)
        .toList()
}

private fun snippet(text: String, term: String): String {
    val at = text.lowercase().indexOf(term)
    val from = (at - SNIPPET_AROUND).coerceAtLeast(0)
    val to = (at + term.length + SNIPPET_AROUND).coerceAtMost(text.length)
    val body = text.substring(from, to).replace(Regex("\\s+"), " ").trim()
    return (if (from > 0) "…" else "") + body + (if (to < text.length) "…" else "")
}
