package dev.sophi.schedule.engine

import dev.sophi.core.tools.ConfirmationPolicy

class AllowlistConfirmationPolicy(private val allowlist: Set<String>) : ConfirmationPolicy {
    override suspend fun confirm(toolName: String, argumentsJson: String): Boolean = toolName in allowlist
}
