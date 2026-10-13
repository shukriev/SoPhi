package dev.sophi.extensions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class PluginRegistryPlacementTest : FunSpec({
    fun plugin(name: String, p: Placement?, text: String?, slow: Boolean = false, boom: Boolean = false) =
        object : SophiPlugin, ContextContributor {
            override val name = name
            override fun hooks() = emptyList<AgentHook>()
            override val placement get() = p ?: Placement.STABLE
            override suspend fun contribute(sessionId: String, userInput: String): String? {
                if (slow) delay(5_000); if (boom) error("boom"); return text
            }
        }

    test("contributions carry their placement; default is STABLE; slow or failing ones yield nothing") {
        val r = PluginRegistry()
        r.register(plugin("a", null, "stable-default"))
        r.register(plugin("b", Placement.VOLATILE, "volatile"))
        r.register(plugin("c", Placement.VOLATILE, "late", slow = true))
        r.register(plugin("d", Placement.STABLE, "x", boom = true))
        r.register(plugin("e", Placement.STABLE, null))
        runBlocking { r.collectPlacedContext("s", "hi", timeoutMillis = 200) } shouldBe listOf(
            PlacedContext(Placement.STABLE, "stable-default"), PlacedContext(Placement.VOLATILE, "volatile"))
    }
})
