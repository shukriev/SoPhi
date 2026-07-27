package dev.sophi.schedule.engine

import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ConfirmationRequest

class AllowlistConfirmationPolicy(private val allowlist: Set<String>) : ConfirmationPolicy {
    override suspend fun confirm(requests: List<ConfirmationRequest>): Map<String, Boolean> =
        requests.associate { it.callId to (it.toolName in allowlist) }
}
