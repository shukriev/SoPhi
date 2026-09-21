package dev.sophi.sdk

import dev.sophi.skills.Skill
import dev.sophi.skills.SkillMetadata
import dev.sophi.skills.SkillRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

private fun skill(title: String, body: String) =
    Skill(SkillMetadata(title = title), body, Path.of("/tmp/$title.md"))

private val MAIDPLUS = skill(
    "Maidplus",
    """
    ## Map
    Entry: https://maidplus.de

    ## Procedures

    ### Create a client
    1. Click "New client".
    """.trimIndent()
)

private fun registryOf(vararg entries: Pair<String, Skill>) = SkillRegistry(entries.toMap())

class SiteSkillRecallPluginTest : FunSpec({

    test("contributes a pointer when the message names a known site") {
        val plugin = SiteSkillRecallPlugin { registryOf("site-maidplus-de" to MAIDPLUS) }

        val contribution = runBlocking { plugin.contribute("s1", "open https://maidplus.de and add a client") }

        contribution!! shouldContain "site-maidplus-de"
        contribution shouldContain "Create a client"
    }

    test("contributes nothing when no skill matches") {
        val plugin = SiteSkillRecallPlugin { registryOf("site-maidplus-de" to MAIDPLUS) }

        runBlocking { plugin.contribute("s1", "what is the weather today") } shouldBe null
    }

    test("contributes nothing when there are no skills at all") {
        val plugin = SiteSkillRecallPlugin { registryOf() }

        runBlocking { plugin.contribute("s1", "open https://maidplus.de") } shouldBe null
    }

    test("the registry is re-read per call, so a skill written this session is recalled") {
        // skillTools() snapshots the registry at build time; recall must not inherit that staleness
        // or a skill Sophi wrote minutes ago stays invisible until restart.
        var current = registryOf()
        val plugin = SiteSkillRecallPlugin { current }

        runBlocking { plugin.contribute("s1", "open https://maidplus.de") } shouldBe null
        current = registryOf("site-maidplus-de" to MAIDPLUS)

        runBlocking { plugin.contribute("s1", "open https://maidplus.de") }!! shouldContain "site-maidplus-de"
    }

    test("a failing registry supplier yields no contribution rather than throwing") {
        // collectContext catches, but a contributor that throws still costs the turn its other
        // contributions' ordering; failing quietly is the contract MemoryPlugin also follows.
        val plugin = SiteSkillRecallPlugin { error("disk gone") }

        runBlocking { plugin.contribute("s1", "open https://maidplus.de") } shouldBe null
    }
})
