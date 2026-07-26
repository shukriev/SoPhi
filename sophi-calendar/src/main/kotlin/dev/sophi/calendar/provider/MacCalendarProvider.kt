package dev.sophi.calendar.provider

import dev.sophi.calendar.model.CalendarEvent
import dev.sophi.calendar.model.CalendarEventPatch
import dev.sophi.calendar.model.CalendarInfo
import dev.sophi.calendar.model.Recurrence
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val FIELD_SEP = "::SOPHI_FIELD::"

class MacCalendarProvider(
    private val runScript: (String) -> String = { script ->
        val process = ProcessBuilder("osascript", "-e", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output
    }
) : CalendarProvider {

    override fun listCalendars(): List<CalendarInfo> {
        val script = """
            tell application "Calendar"
                set outputLines to {}
                repeat with c in calendars
                    set end of outputLines to (name of c)
                end repeat
                set {tid, my text item delimiters} to {my text item delimiters, linefeed}
                set result to outputLines as text
                set my text item delimiters to tid
                return result
            end tell
        """.trimIndent()
        val output = runScript(script).trim()
        if (output.isEmpty()) return emptyList()
        return output.lines().mapIndexed { i, name -> CalendarInfo(id = name, name = name, isDefault = i == 0) }
    }

    internal fun defaultCalendarName(): String =
        listCalendars().firstOrNull()?.id ?: error("No calendars found on this system")

    internal fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    internal fun epochMsToAppleScriptDate(epochMs: Long, varName: String): String {
        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
        val secondsSinceMidnight = zdt.hour * 3600 + zdt.minute * 60 + zdt.second
        return """
            set $varName to current date
            set day of $varName to 1
            set year of $varName to ${zdt.year}
            set month of $varName to ${zdt.monthValue}
            set day of $varName to ${zdt.dayOfMonth}
            set time of $varName to $secondsSinceMidnight
        """.trimIndent()
    }

    internal fun parseIsoDateToEpochMs(isoDate: String): Long =
        LocalDate.parse(isoDate).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    internal fun toRRule(r: Recurrence): String {
        val sb = StringBuilder("FREQ=${r.frequency.name}")
        sb.append(";INTERVAL=${r.interval}")
        r.count?.let { sb.append(";COUNT=$it") }
        r.until?.let {
            val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.of("UTC"))
            sb.append(";UNTIL=${zdt.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))}")
        }
        r.byWeekday?.let { days -> sb.append(";BYDAY=" + days.joinToString(",") { d -> dayCode(d) }) }
        return sb.toString()
    }

    private fun dayCode(d: DayOfWeek): String = when (d) {
        DayOfWeek.MONDAY -> "MO"
        DayOfWeek.TUESDAY -> "TU"
        DayOfWeek.WEDNESDAY -> "WE"
        DayOfWeek.THURSDAY -> "TH"
        DayOfWeek.FRIDAY -> "FR"
        DayOfWeek.SATURDAY -> "SA"
        DayOfWeek.SUNDAY -> "SU"
    }

    // Placeholder overrides implemented in Tasks 4-6; keeps this file compiling
    // standalone for this task's test run.
    override fun create(event: CalendarEvent): CalendarEvent = TODO("Task 4")
    override fun get(eventId: String, calendarId: String?): CalendarEvent? = TODO("Task 5")
    override fun list(calendarId: String?, rangeStartMs: Long, rangeEndMs: Long): List<CalendarEvent> = TODO("Task 5")
    override fun update(eventId: String, calendarId: String?, patch: CalendarEventPatch): CalendarEvent = TODO("Task 6")
    override fun delete(eventId: String, calendarId: String?): Boolean = TODO("Task 6")

    companion object {
        internal fun toRRuleForTest(r: Recurrence): String = MacCalendarProvider().toRRule(r)
    }
}
