package dev.sophi.schedule.engine

/** How a watch run's summary starts when the page had no change (or this was its first look): never notified, left out of the briefing. */
const val WATCH_UNCHANGED = "Page unchanged"

/** How a watch run's summary starts when the page changed; the page's own lines follow on later lines. */
const val PAGE_CHANGED = "Page changed"

private const val SHOWN_LINES = 3
private val ISO_DATE_TIME = Regex("""\b\d{4}-\d{2}-\d{2}[T ]\d{1,2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?""")
private val CLOCK_TIME = Regex("""\b([01]?\d|2[0-3]):[0-5]\d(:[0-5]\d(\.\d+)?)?\b""")
private val SPACES = Regex("""\s+""")

/**
 * Page text as compared between checks: each line's whitespace runs collapsed, blank lines dropped, clock times and
 * ISO date-times masked so a "last updated 14:32" alone is no change.
 * ponytail: only times are masked; a page that prints today's plain date or a view counter changes every day.
 */
fun normalisePage(text: String): String = text.lines()
    .map { it.replace(ISO_DATE_TIME, "<time>").replace(CLOCK_TIME, "<time>").replace(SPACES, " ").trim() }
    .filter { it.isNotEmpty() }
    .joinToString("\n")

/**
 * The run summary for a change from [old] to [new] (both normalised): a first line naming [url] and the counts, then
 * up to [SHOWN_LINES] added and removed lines.
 * ponytail: a set difference, not an LCS diff; a line that only moved or repeats doesn't count.
 */
fun pageChange(url: String, old: String, new: String): String {
    val before = old.lines().filter { it.isNotEmpty() }
    val after = new.lines().filter { it.isNotEmpty() }
    val added = after - before.toSet()
    val removed = before - after.toSet()
    fun show(sign: String, lines: List<String>) = lines.take(SHOWN_LINES).map { "$sign ${it.take(160)}" } +
        listOfNotNull(if (lines.size > SHOWN_LINES) "$sign ${lines.size - SHOWN_LINES} more" else null)
    val counts = if (added.isEmpty() && removed.isEmpty()) "lines moved" else "${added.size} added, ${removed.size} removed"
    return (listOf("$PAGE_CHANGED: $url ($counts)") + show("+", added) + show("-", removed)).joinToString("\n")
}
