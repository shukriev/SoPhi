package dev.sophi.learning

import kotlinx.serialization.json.Json

data class ToolStats(
    val attempts: Int,
    val failures: Int,
    val streak: Int,
    val meanDurationMillis: Long,
    val lastErrors: List<String>
)

class ToolStatsStore(
    private val log: JsonlLog,
    private val window: Int = 5000,
    private val ttlMillis: Long = 60_000
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cache: Map<String, Map<String, ToolStats>>? = null   // scope -> tool -> stats
    private var cacheAt = 0L

    fun stats(scope: String): Map<String, ToolStats> {
        val now = System.currentTimeMillis()
        if (cache == null || now - cacheAt > ttlMillis) {
            cache = rebuild(); cacheAt = now
        }
        return cache!![scope] ?: emptyMap()
    }

    private fun rebuild(): Map<String, Map<String, ToolStats>> {
        val events = log.readLast(window).mapNotNull { line ->
            runCatching { json.decodeFromString(ToolEvent.serializer(), line) }.getOrNull()
        }
        return events.groupBy { it.scope }.mapValues { (_, scoped) ->
            scoped.groupBy { it.tool }.mapValues { (_, evs) ->
                var streak = 0
                for (e in evs.asReversed()) { if (!e.success) streak++ else break }
                ToolStats(
                    attempts = evs.size,
                    failures = evs.count { !it.success },
                    streak = streak,
                    meanDurationMillis = if (evs.isEmpty()) 0 else evs.sumOf { it.durationMillis } / evs.size,
                    lastErrors = evs.filter { !it.success }.takeLast(3).mapNotNull { it.errorSnippet }
                )
            }
        }
    }
}
