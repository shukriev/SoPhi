package dev.sophi.extensions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay

private class FakePlugin(
    override val name: String,
    private val block: suspend (String, String) -> String?
) : SophiPlugin, ContextContributor {
    override fun hooks(): List<AgentHook> = emptyList()
    override suspend fun contribute(sessionId: String, userInput: String): String? =
        block(sessionId, userInput)
}

private class PlainPlugin : SophiPlugin {
    override val name = "plain"
    override fun hooks(): List<AgentHook> = emptyList()
}

class CollectContextTest : FunSpec({
    test("collects non-null contributions in registration order, skipping non-contributors") {
        val registry = PluginRegistry()
            .register(PlainPlugin())
            .register(FakePlugin("a") { _, input -> "A:$input" })
            .register(FakePlugin("b") { _, _ -> null })
            .register(FakePlugin("c") { _, _ -> "C" })
        registry.collectContext("s1", "hi") shouldBe listOf("A:hi", "C")
    }

    test("a throwing contributor is skipped, others still contribute") {
        val registry = PluginRegistry()
            .register(FakePlugin("boom") { _, _ -> error("kaput") })
            .register(FakePlugin("ok") { _, _ -> "OK" })
        registry.collectContext("s1", "hi") shouldBe listOf("OK")
    }

    test("a slow contributor times out and yields nothing") {
        val registry = PluginRegistry()
            .register(FakePlugin("slow") { _, _ -> delay(60_000); "late" })
            .register(FakePlugin("fast") { _, _ -> "fast" })
        registry.collectContext("s1", "hi", timeoutMillis = 50) shouldBe listOf("fast")
    }

    test("HookContext carries assistantReply, defaulting null") {
        HookContext("s1").assistantReply shouldBe null
        HookContext("s1", assistantReply = "done").assistantReply shouldBe "done"
    }
})
