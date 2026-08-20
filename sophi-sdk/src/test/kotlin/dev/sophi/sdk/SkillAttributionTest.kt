package dev.sophi.sdk

import dev.sophi.learning.ToolEvent
import dev.sophi.skills.SkillInvocationEvent
import dev.sophi.skills.SkillVersion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SkillAttributionTest : FunSpec({
    test("a skill-read ToolEvent immediately following an invocation is skipped, not treated as the next action") {
        val version = SkillVersion(id = "v1", ts = 100L, skillId = "site-a", project = false, content = "c")
        val invocation = SkillInvocationEvent(ts = 200L, sessionId = "s1", skillId = "site-a")
        val skillsOwnEvent = ToolEvent(ts = 210L, scope = "/p", sessionId = "s1", tool = "skill", success = true, durationMillis = 1)
        val realNextEvent = ToolEvent(ts = 300L, scope = "/p", sessionId = "s1", tool = "write_file", success = false, durationMillis = 1)

        val result = computeSkillAttribution(listOf(version), listOf(invocation), listOf(skillsOwnEvent, realNextEvent))

        result.single().adjacentFailures shouldBe 1
    }

    test("a failing ToolEvent from a different session is never counted as an adjacent failure") {
        val version = SkillVersion(id = "v1", ts = 100L, skillId = "site-a", project = false, content = "c")
        val invocation = SkillInvocationEvent(ts = 200L, sessionId = "s1", skillId = "site-a")
        val otherSessionFailure = ToolEvent(ts = 300L, scope = "/p", sessionId = "s2", tool = "write_file", success = false, durationMillis = 1)

        val result = computeSkillAttribution(listOf(version), listOf(invocation), listOf(otherSessionFailure))

        result.single().invocationCount shouldBe 1
        result.single().adjacentFailures shouldBe 0
    }

    test("two versions of the same skill recorded at the identical ts do not double-count a shared invocation") {
        val v1 = SkillVersion(id = "v1", ts = 100L, skillId = "site-a", project = false, content = "old")
        val v2 = SkillVersion(id = "v2", ts = 100L, skillId = "site-a", project = false, content = "new")
        val invocation = SkillInvocationEvent(ts = 150L, sessionId = "s1", skillId = "site-a")

        val result = computeSkillAttribution(listOf(v1, v2), listOf(invocation), emptyList())

        result.sumOf { it.invocationCount } shouldBe 1
        // the later-recorded version (v2, matching SkillVersionStore.history()'s own tie-break) owns it
        result.single { it.versionId == "v2" }.invocationCount shouldBe 1
        result.single { it.versionId == "v1" }.invocationCount shouldBe 0
    }

    test("an invocation with no following ToolEvent in its session counts as clean, not a failure") {
        val version = SkillVersion(id = "v1", ts = 100L, skillId = "site-a", project = false, content = "c")
        val invocation = SkillInvocationEvent(ts = 200L, sessionId = "s1", skillId = "site-a")

        val result = computeSkillAttribution(listOf(version), listOf(invocation), emptyList())

        result.single().invocationCount shouldBe 1
        result.single().adjacentFailures shouldBe 0
    }

    test("a skill with recorded versions but zero invocations reports invocationCount = 0") {
        val version = SkillVersion(id = "v1", ts = 100L, skillId = "site-a", project = false, content = "c")

        val result = computeSkillAttribution(listOf(version), emptyList(), emptyList())

        result.single().invocationCount shouldBe 0
        result.single().adjacentFailures shouldBe 0
    }

    test("invocations split across two versions of the same skill bucket by timestamp") {
        val v1 = SkillVersion(id = "v1", ts = 100L, skillId = "site-a", project = false, content = "old")
        val v2 = SkillVersion(id = "v2", ts = 500L, skillId = "site-a", project = false, content = "new")
        val invocationForV1 = SkillInvocationEvent(ts = 200L, sessionId = "s1", skillId = "site-a")
        val invocationForV2 = SkillInvocationEvent(ts = 600L, sessionId = "s2", skillId = "site-a")

        val result = computeSkillAttribution(listOf(v1, v2), listOf(invocationForV1, invocationForV2), emptyList())

        result.single { it.versionId == "v1" }.invocationCount shouldBe 1
        result.single { it.versionId == "v2" }.invocationCount shouldBe 1
    }

    test("SkillVersionAttribution carries project and trial straight from the SkillVersion it attributes") {
        val version = SkillVersion(id = "v1", ts = 100L, skillId = "site-a", project = true, content = "c", trial = true)

        val result = computeSkillAttribution(listOf(version), emptyList(), emptyList())

        result.single().project shouldBe true
        result.single().trial shouldBe true
    }

    test("computeUnattributedInvocationCounts counts an invocation for a skill id with no recorded versions at all") {
        val invocation = SkillInvocationEvent(ts = 100L, sessionId = "s1", skillId = "site-unversioned")

        val result = computeUnattributedInvocationCounts(emptyList(), listOf(invocation))

        result["site-unversioned"] shouldBe 1
    }

    test("computeUnattributedInvocationCounts counts an invocation predating its skill's earliest version") {
        val version = SkillVersion(id = "v1", ts = 500L, skillId = "site-a", project = false, content = "c")
        val earlyInvocation = SkillInvocationEvent(ts = 100L, sessionId = "s1", skillId = "site-a")

        val result = computeUnattributedInvocationCounts(listOf(version), listOf(earlyInvocation))

        result["site-a"] shouldBe 1
    }

    test("computeUnattributedInvocationCounts does not count an invocation covered by an existing version") {
        val version = SkillVersion(id = "v1", ts = 100L, skillId = "site-a", project = false, content = "c")
        val invocation = SkillInvocationEvent(ts = 200L, sessionId = "s1", skillId = "site-a")

        val result = computeUnattributedInvocationCounts(listOf(version), listOf(invocation))

        result.containsKey("site-a") shouldBe false
    }
})
