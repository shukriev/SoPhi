package dev.sophi.extensions

import dev.sophi.core.agent.TurnEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private class RecordingPlugin : SophiPlugin {
    override val name = "recording"
    val contexts = mutableMapOf<HookPoint, MutableList<HookContext>>()
    override fun hooks(): List<AgentHook> = listOf(HookPoint.BEFORE_TOOL, HookPoint.AFTER_TOOL).map { p ->
        object : AgentHook {
            override val point = p
            override suspend fun invoke(context: HookContext) {
                contexts.getOrPut(p) { mutableListOf() }.add(context)
            }
        }
    }
}

class TurnEventBridgeTest : FunSpec({
    test("bridge dispatches ToolCallStarted/Finished to BEFORE_TOOL/AFTER_TOOL with enriched context") {
        val plugin = RecordingPlugin()
        val bridge = PluginRegistry().register(plugin).turnEventBridge("sess-1")

        bridge(TurnEvent.ToolCallStarted("grep", """{"pattern":"x"}"""))
        bridge(TurnEvent.ToolCallFinished("grep", "Error: boom", isError = true, durationMillis = 42))
        bridge(TurnEvent.Token("ignored"))

        val before = plugin.contexts.getValue(HookPoint.BEFORE_TOOL).single()
        before.sessionId shouldBe "sess-1"
        before.toolName shouldBe "grep"
        before.argumentsJson shouldBe """{"pattern":"x"}"""

        val after = plugin.contexts.getValue(HookPoint.AFTER_TOOL).single()
        after.toolResult shouldBe "Error: boom"
        after.success shouldBe false
        after.durationMillis shouldBe 42L
    }
})
