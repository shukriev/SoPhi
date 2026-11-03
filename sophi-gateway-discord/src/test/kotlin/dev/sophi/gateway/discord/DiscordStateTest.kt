package dev.sophi.gateway.discord

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

class DiscordStateTest : FunSpec({
    fun newPath() = Files.createTempDirectory("discord-state").resolve("phone.json")
    val alex = Pairing("u2", "alex", "dm2", "p2")

    test("members persist beside phone.json") {
        val path = newPath()
        DiscordState(path).pairMember(alex)
        DiscordState(path).members shouldBe listOf(alex)
        Files.exists(path) shouldBe false
    }

    test("owner pairing keeps its format and no members file appears") {
        val path = newPath()
        DiscordState(path).pair(Pairing("u1", "sam", "dm1"))
        Files.readString(path) shouldNotContain "personId"
        Files.exists(path.resolveSibling("phone-members.json")) shouldBe false
        DiscordState(path).members.shouldBeEmpty()
    }

    test("re-pairing a person replaces the old pairing") {
        val path = newPath()
        val s = DiscordState(path)
        s.pairMember(alex)
        s.pairMember(Pairing("u3", "alex", "dm3", "p2"))
        s.members.single().userId shouldBe "u3"
        DiscordState(path).members.single().userId shouldBe "u3"
    }

    test("re-pairing a Discord user replaces the old pairing") {
        val s = DiscordState(newPath())
        s.pairMember(alex)
        s.pairMember(Pairing("u2", "alex", "dm2", "p9"))
        s.members.single().personId shouldBe "p9"
    }

    test("unpair a member") {
        val path = newPath()
        val s = DiscordState(path)
        s.pairMember(alex)
        s.unpairMember("p2") shouldBe true
        s.unpairMember("p2") shouldBe false
        s.members.shouldBeEmpty()
        DiscordState(path).members.shouldBeEmpty()
    }

    test("clear removes members too") {
        val path = newPath()
        val s = DiscordState(path)
        s.pairMember(alex)
        s.clear()
        s.members.shouldBeEmpty()
        Files.exists(path.resolveSibling("phone-members.json")) shouldBe false
    }
})
