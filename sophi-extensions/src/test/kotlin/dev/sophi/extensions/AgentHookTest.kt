package dev.sophi.extensions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class AgentHookTest : FunSpec({

    test("AgentHook invoke() fires and receives the context") {
        val invoked = mutableListOf<HookPoint>()
        val hook = object : AgentHook {
            override val point = HookPoint.BEFORE_TURN
            override suspend fun invoke(context: HookContext) { invoked.add(point) }
        }
        hook.invoke(HookContext(sessionId = "s1"))
        invoked shouldBe listOf(HookPoint.BEFORE_TURN)
    }

    test("HookContext carries all optional fields") {
        val err = RuntimeException("boom")
        val ctx = HookContext(
            sessionId = "s2",
            userInput = "hello",
            toolName = "calculator",
            error = err
        )
        ctx.sessionId shouldBe "s2"
        ctx.userInput shouldBe "hello"
        ctx.toolName shouldBe "calculator"
        ctx.error?.message shouldBe "boom"
    }

    test("SophiPlugin default version is 1.0.0") {
        val plugin = object : SophiPlugin {
            override val name = "test-plugin"
            override fun hooks() = emptyList<AgentHook>()
        }
        plugin.version shouldBe "1.0.0"
    }

    test("SophiPlugin returns its registered hooks list") {
        val hook = object : AgentHook {
            override val point = HookPoint.AFTER_TURN
            override suspend fun invoke(context: HookContext) {}
        }
        val plugin = object : SophiPlugin {
            override val name = "hook-plugin"
            override fun hooks() = listOf(hook)
        }
        plugin.hooks() shouldHaveSize 1
        plugin.hooks().first().point shouldBe HookPoint.AFTER_TURN
    }
})
