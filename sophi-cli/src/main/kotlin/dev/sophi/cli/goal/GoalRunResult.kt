package dev.sophi.cli.goal

import dev.sophi.core.session.AgentSession

sealed class GoalRunResult {
    data class Ran(val session: AgentSession) : GoalRunResult()
    data class Declined(val session: AgentSession) : GoalRunResult()
}
