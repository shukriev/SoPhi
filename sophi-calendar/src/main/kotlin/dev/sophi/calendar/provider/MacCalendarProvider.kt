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

private val HANDLERS_PRELUDE = """
    on clean(t)
        if t is missing value then return ""
        set t to t as text
        set {tid, my text item delimiters} to {my text item delimiters, return}
        set parts to text items of t
        set my text item delimiters to " "
        set t to parts as text
        set my text item delimiters to tid
        return t
    end clean

    on formatEvent(e)
        set sd to start date of e
        set ed to end date of e
        return (uid of e) & "$FIELD_SEP" & (summary of e as text) & "$FIELD_SEP" & ¬
            (year of sd as text) & "$FIELD_SEP" & (month of sd as integer as text) & "$FIELD_SEP" & (day of sd as text) & "$FIELD_SEP" & (time of sd as text) & "$FIELD_SEP" & ¬
            (year of ed as text) & "$FIELD_SEP" & (month of ed as integer as text) & "$FIELD_SEP" & (day of ed as text) & "$FIELD_SEP" & (time of ed as text) & "$FIELD_SEP" & ¬
            (allday event of e as text) & "$FIELD_SEP" & my clean(location of e) & "$FIELD_SEP" & my clean(description of e)
    end formatEvent
""".trimIndent()

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
    override fun create(event: CalendarEvent): CalendarEvent {
        val calendarName = event.calendarId ?: defaultCalendarName()
        val startMs = if (event.allDay) {
            parseIsoDateToEpochMs(requireNotNull(event.startDate) { "startDate is required when allDay=true" })
        } else event.start
        val endMs = if (event.allDay) {
            parseIsoDateToEpochMs(requireNotNull(event.endDate) { "endDate is required when allDay=true" })
        } else event.end

        val props = StringBuilder("summary:${quote(event.title)}, start date:newStart, end date:newEnd")
        event.location?.let { props.append(", location:${quote(it)}") }
        event.notes?.let { props.append(", description:${quote(it)}") }
        if (event.allDay) props.append(", allday event:true")

        val recurrenceLine = event.recurrence?.let { "\n                set recurrence of newEvent to ${quote(toRRule(it))}" } ?: ""
        val reminderLine = event.reminderMinutesBefore?.let {
            "\n                make new display alarm at end of display alarms of newEvent with properties {trigger interval:-$it}"
        } ?: ""

        val script = """
            ${epochMsToAppleScriptDate(startMs, "newStart")}
            ${epochMsToAppleScriptDate(endMs, "newEnd")}
            tell application "Calendar"
                tell calendar ${quote(calendarName)}
                    set newEvent to make new event with properties {$props}$recurrenceLine$reminderLine
                    return uid of newEvent
                end tell
            end tell
        """.trimIndent()

        val id = runScript(script).trim()
        return event.copy(id = id, calendarId = calendarName)
    }
    override fun get(eventId: String, calendarId: String?): CalendarEvent? {
        val calendarName = calendarId ?: defaultCalendarName()
        val script = """
            $HANDLERS_PRELUDE
            tell application "Calendar"
                tell calendar ${quote(calendarName)}
                    try
                        set theEvent to (first event whose uid is ${quote(eventId)})
                    on error
                        return "NOT_FOUND"
                    end try
                    return my formatEvent(theEvent)
                end tell
            end tell
        """.trimIndent()
        val output = runScript(script).trim()
        if (output == "NOT_FOUND" || output.isEmpty()) return null
        return parseEventLine(output, calendarName)
    }

    override fun list(calendarId: String?, rangeStartMs: Long, rangeEndMs: Long): List<CalendarEvent> {
        val calendarName = calendarId ?: defaultCalendarName()
        val script = """
            $HANDLERS_PRELUDE
            ${epochMsToAppleScriptDate(rangeStartMs, "rangeStart")}
            ${epochMsToAppleScriptDate(rangeEndMs, "rangeEnd")}
            tell application "Calendar"
                tell calendar ${quote(calendarName)}
                    set matches to (every event whose start date < rangeEnd and end date > rangeStart)
                    set outputLines to {}
                    repeat with e in matches
                        set end of outputLines to my formatEvent(e)
                    end repeat
                    set {tid, my text item delimiters} to {my text item delimiters, linefeed}
                    set result to outputLines as text
                    set my text item delimiters to tid
                    return result
                end tell
            end tell
        """.trimIndent()
        val output = runScript(script).trim()
        if (output.isEmpty()) return emptyList()
        return output.lines().map { parseEventLine(it, calendarName) }
    }

    private fun parseEventLine(line: String, calendarName: String): CalendarEvent {
        val f = line.split(FIELD_SEP)
        return CalendarEvent(
            id = f[0],
            calendarId = calendarName,
            title = f[1],
            start = ymdSecToEpochMs(f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt()),
            end = ymdSecToEpochMs(f[6].toInt(), f[7].toInt(), f[8].toInt(), f[9].toInt()),
            allDay = f[10] == "true",
            location = f[11].ifBlank { null },
            notes = f[12].ifBlank { null }
        )
    }

    private fun ymdSecToEpochMs(year: Int, month: Int, day: Int, secondsSinceMidnight: Int): Long =
        ZonedDateTime.of(
            year, month, day,
            secondsSinceMidnight / 3600, (secondsSinceMidnight % 3600) / 60, secondsSinceMidnight % 60, 0,
            ZoneId.systemDefault()
        ).toInstant().toEpochMilli()
    override fun update(eventId: String, calendarId: String?, patch: CalendarEventPatch): CalendarEvent {
        val calendarName = calendarId ?: defaultCalendarName()
        val dateSetup = StringBuilder()
        val setters = StringBuilder()

        patch.title?.let { setters.append("\n                    set summary of theEvent to ${quote(it)}") }
        patch.location?.let { setters.append("\n                    set location of theEvent to ${quote(it)}") }
        patch.notes?.let { setters.append("\n                    set description of theEvent to ${quote(it)}") }
        patch.allDay?.let { setters.append("\n                    set allday event of theEvent to $it") }

        val startMs = if (patch.allDay == true && patch.startDate != null) parseIsoDateToEpochMs(patch.startDate) else patch.start
        startMs?.let {
            dateSetup.append(epochMsToAppleScriptDate(it, "newStart")).append("\n")
            setters.append("\n                    set start date of theEvent to newStart")
        }
        val endMs = if (patch.allDay == true && patch.endDate != null) parseIsoDateToEpochMs(patch.endDate) else patch.end
        endMs?.let {
            dateSetup.append(epochMsToAppleScriptDate(it, "newEnd")).append("\n")
            setters.append("\n                    set end date of theEvent to newEnd")
        }

        if (patch.clearRecurrence) {
            setters.append("\n                    set recurrence of theEvent to \"\"")
        } else {
            patch.recurrence?.let { setters.append("\n                    set recurrence of theEvent to ${quote(toRRule(it))}") }
        }
        patch.reminderMinutesBefore?.let {
            setters.append("\n                    if (count of display alarms of theEvent) > 0 then delete display alarm 1 of theEvent")
            setters.append("\n                    make new display alarm at end of display alarms of theEvent with properties {trigger interval:-$it}")
        }

        val script = """
            $HANDLERS_PRELUDE
            $dateSetup
            tell application "Calendar"
                tell calendar ${quote(calendarName)}
                    try
                        set theEvent to (first event whose uid is ${quote(eventId)})
                    on error
                        return "NOT_FOUND"
                    end try$setters
                    return my formatEvent(theEvent)
                end tell
            end tell
        """.trimIndent()

        val output = runScript(script).trim()
        check(output != "NOT_FOUND") { "No event found with id $eventId" }
        return parseEventLine(output, calendarName)
    }

    override fun delete(eventId: String, calendarId: String?): Boolean {
        val calendarName = calendarId ?: defaultCalendarName()
        val script = """
            tell application "Calendar"
                tell calendar ${quote(calendarName)}
                    try
                        set theEvent to (first event whose uid is ${quote(eventId)})
                        delete theEvent
                        return "DELETED"
                    on error
                        return "NOT_FOUND"
                    end try
                end tell
            end tell
        """.trimIndent()
        return runScript(script).trim() == "DELETED"
    }

    companion object {
        internal fun toRRuleForTest(r: Recurrence): String = MacCalendarProvider().toRRule(r)
    }
}
