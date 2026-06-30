package dev.sophi.extensions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class PluginRegistryTest : FunSpec({

    fun makeHook(p: HookPoint, log: MutableList<String>, id: String): AgentHook =
        object : AgentHook {
            override val point = p
            override suspend fun invoke(context: HookContext) { log.add(id) }
        }

    fun makePlugin(pluginName: String, vararg hooks: AgentHook): SophiPlugin =
        object : SophiPlugin {
            override val name = pluginName
            override fun hooks() = hooks.toList()
        }

    test("register() adds a plugin and plugins() returns it") {
        val registry = PluginRegistry()
        val plugin = makePlugin("p1")
        registry.register(plugin)
        registry.plugins() shouldBe listOf(plugin)
    }

    test("hooksFor() returns only hooks matching the requested point") {
        val registry = PluginRegistry()
        val log = mutableListOf<String>()
        val h1 = makeHook(HookPoint.BEFORE_TURN, log, "h1")
        val h2 = makeHook(HookPoint.AFTER_TURN, log, "h2")
        registry.register(makePlugin("p1", h1, h2))
        registry.hooksFor(HookPoint.BEFORE_TURN) shouldBe listOf(h1)
        registry.hooksFor(HookPoint.AFTER_TURN) shouldBe listOf(h2)
    }

    test("dispatch() invokes all hooks for the given point in registration order") {
        val registry = PluginRegistry()
        val log = mutableListOf<String>()
        registry.register(makePlugin("p1",
            makeHook(HookPoint.BEFORE_TURN, log, "first"),
            makeHook(HookPoint.BEFORE_TURN, log, "second")
        ))
        registry.dispatch(HookPoint.BEFORE_TURN, HookContext(sessionId = "s1"))
        log shouldBe listOf("first", "second")
    }

    test("dispatch() does not invoke hooks registered for other points") {
        val registry = PluginRegistry()
        val log = mutableListOf<String>()
        registry.register(makePlugin("p1", makeHook(HookPoint.AFTER_TOOL, log, "h")))
        registry.dispatch(HookPoint.BEFORE_TURN, HookContext(sessionId = "s1"))
        log.shouldBeEmpty()
    }

    test("register() returns registry instance for chaining") {
        val registry = PluginRegistry()
        registry.register(makePlugin("p1")) shouldBeSameInstanceAs registry
    }
})
