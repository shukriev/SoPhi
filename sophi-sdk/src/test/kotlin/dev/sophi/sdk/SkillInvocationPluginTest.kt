package dev.sophi.sdk

import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.skills.SkillInvocationStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory

class SkillInvocationPluginTest : FunSpec({
    fun hook(store: SkillInvocationStore) = SkillInvocationPlugin(store).hooks().single { it.point == HookPoint.BEFORE_TOOL }
    fun store(): SkillInvocationStore = SkillInvocationStore(createTempDirectory("skill-invocation-plugin").resolve(".invocations.jsonl"))

    test("BEFORE_TOOL for skill persists an invocation keyed by the dispatch's sessionId and name arg") {
        val store = store()
        runBlocking {
            hook(store).invoke(HookContext(
                sessionId = "s1", toolName = "skill",
                argumentsJson = """{"name":"site-example-com"}"""
            ))
        }
        val events = store.all()
        events shouldHaveSize 1
        events.single().sessionId shouldBe "s1"
        events.single().skillId shouldBe "site-example-com"
    }

    test("BEFORE_TOOL for any other tool name is a no-op") {
        val store = store()
        runBlocking {
            hook(store).invoke(HookContext(sessionId = "s1", toolName = "write_skill", argumentsJson = """{"id":"site-example-com"}"""))
        }
        store.all() shouldBe emptyList()
    }

    test("malformed argumentsJson does not throw and persists nothing") {
        val store = store()
        runBlocking {
            hook(store).invoke(HookContext(sessionId = "s1", toolName = "skill", argumentsJson = "not json"))
        }
        store.all() shouldBe emptyList()
    }

    test("a null argumentsJson does not throw and persists nothing") {
        val store = store()
        runBlocking {
            hook(store).invoke(HookContext(sessionId = "s1", toolName = "skill", argumentsJson = null))
        }
        store.all() shouldBe emptyList()
    }

    test("a missing name argument does not throw and persists nothing") {
        val store = store()
        runBlocking {
            hook(store).invoke(HookContext(sessionId = "s1", toolName = "skill", argumentsJson = "{}"))
        }
        store.all() shouldBe emptyList()
    }
})
