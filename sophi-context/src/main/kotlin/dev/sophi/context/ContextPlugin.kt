package dev.sophi.context

import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.SophiPlugin

/**
 * After the agent changes files, ask for a (cheap, stat-first) rescan of the projects in scope —
 * spec §5.3. AFTER_TOOL carries no arguments, and a project scan covers whatever bash touched too.
 */
class ContextPlugin(private val engine: ContextEngine, private val scope: () -> List<String>) : SophiPlugin {
    override val name = "context"
    private val writers = setOf("write_file", "edit_file", "bash")

    override fun hooks(): List<AgentHook> = listOf(object : AgentHook {
        override val point = HookPoint.AFTER_TOOL
        override suspend fun invoke(context: HookContext) {
            if (context.success == true && context.toolName in writers) scope().forEach { engine.requestScan(it) }
        }
    })
}
