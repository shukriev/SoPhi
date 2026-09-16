package dev.sophi.memory.tools

import dev.sophi.core.tools.RiskLevel
import dev.sophi.core.tools.Tool
import dev.sophi.memory.jane.JanesPalace
import java.time.DayOfWeek

class ListHabitsTool(private val palace: JanesPalace) : Tool {
    override val name = "list_habits"
    override val description = "List the user's remembered habits — recurring things tied to a " +
        "consistent time of day or day of week (e.g. \"usually reviews budget around 8pm\", " +
        "\"checks in on this project every Monday\"). Use this to time a proactive nudge instead " +
        "of guessing, or alongside list_actionable_patterns/calendar tools."
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.SAFE

    override suspend fun execute(argumentsJson: String): String {
        val habits = palace.habits()
        if (habits.isEmpty()) return "No habits remembered yet."
        return habits.joinToString("\n") { m ->
            val day = m.habitPreferredDayOfWeek?.let {
                DayOfWeek.of(it).name.lowercase().replaceFirstChar { c -> c.uppercase() } + "s, "
            } ?: ""
            "- [${m.id}] ${m.text} (${day}around ${m.habitPreferredHour}:00, confidence ${"%.2f".format(m.habitConfidence)})"
        }
    }
}
