package dev.sophi.memory.jane

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure statistics behind [Consolidator.classifyHabits] -- no LLM call, no store access. Hours
 * wrap at 24 (23 and 0 are one apart, not twenty-three), so a plain arithmetic mean is wrong for
 * e.g. [23, 0, 1] (arithmetic mean ~8, actual center ~0) -- this uses the standard
 * circular-mean-via-unit-vectors technique instead.
 */
fun circularHourConcentration(hours: List<Int>): Pair<Int, Double> {
    if (hours.isEmpty()) return 0 to 0.0
    val radians = hours.map { it * 2 * PI / 24 }
    val meanX = radians.sumOf { cos(it) } / hours.size
    val meanY = radians.sumOf { sin(it) } / hours.size
    val meanAngle = atan2(meanY, meanX)
    val meanHour = (((meanAngle / (2 * PI) * 24) + 24).roundToInt()) % 24
    val within = hours.count { circularHourDistance(it, meanHour) <= 1 }
    return meanHour to within.toDouble() / hours.size
}

private fun circularHourDistance(a: Int, b: Int): Int {
    val diff = abs(a - b) % 24
    return minOf(diff, 24 - diff)
}

/** [days] are ISO day-of-week values (1=Monday..7=Sunday, matching [java.time.DayOfWeek.getValue]). */
fun modalDayConcentration(days: List<Int>): Pair<Int, Double> {
    if (days.isEmpty()) return 1 to 0.0
    val mode = days.groupingBy { it }.eachCount().entries.maxBy { it.value }
    return mode.key to mode.value.toDouble() / days.size
}
