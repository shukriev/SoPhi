package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import dev.sophi.skills.SkillInvocationStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

private const val TEST_CONTEXT_WINDOW = 100_000

class RuntimeBuilderSkillInvocationTest : FunSpec({
    test("build() registers a SkillInvocationPlugin that records a skill read under skillsDir") {
        val skillsDir = tempdir().toPath()
        val rt = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
            this.skillsDir = skillsDir
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).build()

        runBlocking {
            rt.pluginRegistry.dispatch(
                HookPoint.BEFORE_TOOL,
                HookContext(sessionId = "s1", toolName = "skill", argumentsJson = """{"name":"site-example-com"}""")
            )
        }

        val events = SkillInvocationStore(skillsDir.resolve(".invocations.jsonl")).all()
        events shouldHaveSize 1
        events.single().skillId shouldBe "site-example-com"
    }
})
