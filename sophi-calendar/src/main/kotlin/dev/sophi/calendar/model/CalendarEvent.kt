package dev.sophi.calendar.model

data class CalendarEvent(
    val id: String? = null,           // null on create; assigned by the backend
    val calendarId: String? = null,   // null = OS default calendar
    val title: String,
    val start: Long = 0,              // epoch millis (instant) — unused when allDay
    val end: Long = 0,
    val allDay: Boolean = false,
    val startDate: String? = null,    // ISO local date "YYYY-MM-DD" — used only when allDay
    val endDate: String? = null,
    val location: String? = null,
    val notes: String? = null,
    val reminderMinutesBefore: Int? = null,
    val recurrence: Recurrence? = null
)

// Distinct from CalendarEvent: every field is optional so `update` can express
// "leave unchanged" (null/absent) vs. an explicit new value. `clearRecurrence`
// is separate from `recurrence` so "remove recurrence" and "don't touch it"
// aren't both represented by the same null.
data class CalendarEventPatch(
    val title: String? = null,
    val start: Long? = null,
    val end: Long? = null,
    val allDay: Boolean? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val location: String? = null,
    val notes: String? = null,
    val reminderMinutesBefore: Int? = null,
    val recurrence: Recurrence? = null,
    val clearRecurrence: Boolean = false
)
