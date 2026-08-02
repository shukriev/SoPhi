package dev.sophi.cli.goal

import dev.sophi.core.session.AgentSession

sealed class GoalTrigger {
    object Explicit : GoalTrigger()
    data class Autonomous(val source: TriggerSource) : GoalTrigger()
}

enum class TriggerSource { Rules, Llm }

sealed class GoalRunResult {
    data class Ran(val session: AgentSession) : GoalRunResult()
    data class Declined(val session: AgentSession, val reason: DeclineReason) : GoalRunResult()
}

enum class DeclineReason { UserDeclined, Failed }

fun GoalTrigger.recordSource(): String = when (this) {
    is GoalTrigger.Explicit -> "explicit"
    is GoalTrigger.Autonomous -> source.name.lowercase()
}
