package dev.sophi.cli

import dev.sophi.core.agent.AgentConfig
import dev.sophi.core.permissions.Effect
import dev.sophi.core.permissions.PermissionRule
import dev.sophi.core.permissions.PermissionRules
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class SlashPermissionsTest : FunSpec({
    fun setup(): Pair<PermissionRules, MutableList<String>> {
        val dir = Files.createTempDirectory("perm")
        Files.writeString(dir.resolve("g.json"), """[{"tool":"bash","pattern":"rm *","effect":"deny"}]""")
        Files.writeString(dir.resolve("p.json"), """[{"tool":"bash","pattern":"git status","effect":"allow"},{"tool":"fetch_url","effect":"ask"}]""")
        return PermissionRules.load(dir.resolve("g.json"), dir.resolve("p.json"), dir.resolve("t.json")) to mutableListOf()
    }
    fun handler(rules: PermissionRules?, out: MutableList<String>) =
        SlashHandler(mockk<SessionManager>(relaxed = true), null, AgentConfig(model = "m"), permissions = rules) { out += it }

    test("/permissions lists active and untrusted rules") {
        val (rules, out) = setup()
        runBlocking { handler(rules, out).handle("/permissions", AgentSession(id = "s")) }
        val text = out.joinToString("\n")
        text shouldContain "deny bash rm *  (global)"
        text shouldContain "ask fetch_url (any call)  (project)"
        text shouldContain "allow bash git status  (project, untrusted — /permissions trust)"
    }

    test("/permissions shows kind rules by what they match") {
        val dir = Files.createTempDirectory("perm")
        Files.writeString(dir.resolve("g.json"), """[{"kind":"network","effect":"deny","unattended":true},{"kind":"writes","pattern":"src/*","effect":"ask"}]""")
        val out = mutableListOf<String>()
        runBlocking { handler(PermissionRules.load(dir.resolve("g.json"), dir.resolve("p.json"), dir.resolve("t.json")), out).handle("/permissions", AgentSession(id = "s")) }
        val text = out.joinToString("\n")
        text shouldContain "deny any network call (any call), unattended runs only  (global)"
        text shouldContain "ask any writes call src/*  (global)"
    }

    test("/permissions trust activates them") {
        val (rules, out) = setup()
        runBlocking { handler(rules, out).handle("/permissions trust", AgentSession(id = "s")) }
        out shouldBe listOf("Trusted 1 project allow rule(s).")
        rules.untrusted shouldBe emptyList()
        rules.active.contains(PermissionRule("bash", "git status", Effect.ALLOW)) shouldBe true
    }

    test("/permissions with no rules configured says they are off") {
        val out = mutableListOf<String>()
        runBlocking { handler(null, out).handle("/permissions", AgentSession(id = "s")) }
        out shouldBe listOf("Permission rules are off.")
    }
})
