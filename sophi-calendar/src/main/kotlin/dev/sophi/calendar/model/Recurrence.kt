package dev.sophi.calendar.model

import java.time.DayOfWeek

enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

data class Recurrence(
    val frequency: Frequency,
    val interval: Int = 1,
    val count: Int? = null,           // mutually exclusive with `until`
    val until: Long? = null,          // epoch millis
    val byWeekday: List<DayOfWeek>? = null   // WEEKLY only
)
