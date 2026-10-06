package dev.sophi.sdk

import dev.sophi.ai.api.LLMProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlin.io.path.writeText

private const val TEST_CONTEXT_WINDOW = 100_000

class RuntimeBuilderSkillToolsTest : FunSpec({
    test("skillTools() registers skill/install_skill/write_skill, skill only when the registry is non-empty") {
        val globalDir = tempdir().toPath()
        globalDir.resolve("greet.md").writeText("---\ntitle: Greet\ndescription: says hi\n---\nSay hello.")
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
            skillsDir = globalDir
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).skillTools().build()

        val names = runtime.toolNames()
        names shouldContain "skill"
        names shouldContain "install_skill"
        names shouldContain "write_skill"
    }

    test("skillTools() with an empty skills dir registers skill anyway, so skills learned later show up") {
        val runtime = RuntimeBuilder().apply {
            provider = mockk<LLMProvider>()
            sessionsDir = tempdir().toPath()
            skillsDir = tempdir().toPath()
        }.contextWindowTokens(TEST_CONTEXT_WINDOW).skillTools().build()

        val names = runtime.toolNames()
        names shouldContain "skill"
        names shouldContain "install_skill"
        names shouldContain "write_skill"
    }

    test("the skill loader hides auto-* skills while includeAutoSkills is false, and follows it live") {
        val dir = tempdir().toPath()
        dir.resolve("auto-x.md").writeText("---\ntitle: X\ndescription: auto one\n---\nbody")
        dir.resolve("mine.md").writeText("---\ntitle: Mine\ndescription: hand one\n---\nbody")
        var include = false
        val load = skillRegistryLoader(dir, tempdir().toPath()) { include }
        load().all().map { it.first } shouldBe listOf("mine")
        include = true
        load().all().map { it.first } shouldBe listOf("auto-x", "mine")
    }

    test("the skill loader only re-parses when a skill file is added, removed or changed") {
        val dir = tempdir().toPath()
        dir.resolve("mine.md").writeText("---\ntitle: Mine\ndescription: one\n---\nbody")
        val load = skillRegistryLoader(dir, tempdir().toPath()) { true }
        val first = load()
        (load() === first) shouldBe true
        dir.resolve("auto-x.md").writeText("---\ntitle: X\ndescription: two\n---\nbody")
        val second = load()
        (second === first) shouldBe false
        second.all().map { it.first } shouldBe listOf("auto-x", "mine")
        dir.resolve("mine.md").writeText("---\ntitle: Mine\ndescription: changed text\n---\nbody")
        load().get("mine")!!.metadata.description shouldBe "changed text"
    }
})
