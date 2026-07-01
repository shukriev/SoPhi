package dev.sophi.infra

import dev.sophi.extensions.AgentHook
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.extensions.SophiPlugin
import io.micrometer.core.instrument.MeterRegistry

class MetricsPlugin(private val registry: MeterRegistry) : SophiPlugin {
    override val name: String = "metrics"

    override fun hooks(): List<AgentHook> = listOf(
        object : AgentHook {
            override val point = HookPoint.BEFORE_TURN
            override suspend fun invoke(context: HookContext) {
                registry.counter("sophi.turns.started", "session", context.sessionId).increment()
            }
        },
        object : AgentHook {
            override val point = HookPoint.AFTER_TURN
            override suspend fun invoke(context: HookContext) {
                registry.counter("sophi.turns.completed", "session", context.sessionId).increment()
            }
        },
        object : AgentHook {
            override val point = HookPoint.ON_ERROR
            override suspend fun invoke(context: HookContext) {
                registry.counter("sophi.turns.errors", "session", context.sessionId).increment()
            }
        }
    )
}
