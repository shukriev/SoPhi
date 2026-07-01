package dev.sophi.infra

import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.SophiPlugin

class PermissionGatePlugin(private val allowedTools: Set<String>) : SophiPlugin {
    override val name: String = "permission-gate"

    override fun hooks(): List<AgentHook> = listOf(
        object : AgentHook {
            override val point = HookPoint.BEFORE_TOOL
            override suspend fun invoke(context: HookContext) {
                val toolName = context.toolName ?: return
                if (toolName !in allowedTools) {
                    throw SecurityException("Tool '$toolName' is not in the permission allowlist")
                }
            }
        }
    )
}
