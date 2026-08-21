package dev.sophi.skills

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class SkillInvocationStoreTest : FunSpec({
    fun store(): SkillInvocationStore = SkillInvocationStore(createTempDirectory("skill-invocation-store").resolve(".invocations.jsonl"))

    test("record then all() round-trips recorded invocations") {
        val store = store()
        store.record(SkillInvocationEvent(ts = 1L, sessionId = "s1", skillId = "site-example-com"))
        store.record(SkillInvocationEvent(ts = 2L, sessionId = "s1", skillId = "site-other-com"))

        val all = store.all()

        all shouldHaveSize 2
        all[0].skillId shouldBe "site-example-com"
        all[1].sessionId shouldBe "s1"
    }

    test("all() returns an empty list when no invocations were ever recorded") {
        store().all() shouldBe emptyList()
    }

    test("an embedded newline in a field does not corrupt the JSONL framing of the following record") {
        val store = store()
        store.record(SkillInvocationEvent(ts = 1L, sessionId = "s1\nrogue-line", skillId = "site-a"))
        store.record(SkillInvocationEvent(ts = 2L, sessionId = "s2", skillId = "site-b"))

        val all = store.all()

        all shouldHaveSize 2
        all[1].skillId shouldBe "site-b"
    }
})
