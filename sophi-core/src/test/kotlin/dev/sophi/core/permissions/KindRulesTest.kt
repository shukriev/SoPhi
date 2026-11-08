package dev.sophi.core.permissions

import dev.sophi.core.permissions.Effect.ALLOW
import dev.sophi.core.permissions.Effect.ASK
import dev.sophi.core.permissions.Effect.DENY
import dev.sophi.core.tools.EffectKind
import dev.sophi.core.tools.EffectKind.NETWORK
import dev.sophi.core.tools.EffectKind.PROCESS
import dev.sophi.core.tools.EffectKind.WRITES
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

private fun rules(vararg r: PermissionRule) = PermissionRules(r.toList(), emptyList(), null, null, emptySet())
private fun s(vararg t: String) = t.map { PermissionSubject(it) }

class KindRulesTest : FunSpec({
    val net = setOf(NETWORK)

    test("rules without a kind decide exactly as before, whatever the call's effects or who is watching") {
        val old = rules(
            PermissionRule("bash", "git *", ALLOW), PermissionRule("bash", "rm *", DENY), PermissionRule("write_file", null, ASK),
            PermissionRule("fetch_*", "https://internal.*", ALLOW), PermissionRule("*", "*.env", DENY),
        )
        val calls = listOf("bash" to s("git status"), "bash" to s("rm -rf x"), "bash" to s("ls"), "write_file" to s("a.txt"),
            "fetch_url" to s("https://internal.x"), "read_file" to s("prod.env"), "read_file" to null, "todo" to emptyList())
        val kinds = listOf(emptySet(), net, setOf(WRITES, PROCESS), EffectKind.entries.toSet())
        for ((tool, subjects) in calls) {
            val before = old.decide(tool, subjects)
            for (k in kinds) for (u in listOf(true, false)) old.decide(tool, subjects, k, u) shouldBe before
        }
    }

    test("a kind rule matches every tool whose call has that kind, and only those") {
        val r = rules(PermissionRule(kind = NETWORK, effect = DENY))
        r.decide("fetch_url", s("https://x.dev"), net, false) shouldBe DENY
        r.decide("web_search", emptyList(), net, false) shouldBe DENY
        r.decide("read_file", s("a"), emptySet(), false) shouldBe null
    }

    test("an unattended rule applies only when nobody is there") {
        val r = rules(PermissionRule(kind = NETWORK, effect = DENY, unattended = true))
        r.decide("fetch_url", s("https://x.dev"), net, unattended = true) shouldBe DENY
        r.decide("fetch_url", s("https://x.dev"), net, unattended = false) shouldBe null
    }

    test("a kind rule's pattern still matches subjects; deny wins between tool and kind rules either way") {
        rules(PermissionRule(kind = NETWORK, pattern = "https://internal.*", effect = ALLOW))
            .decide("fetch_url", s("https://internal.x"), net, false) shouldBe ALLOW
        rules(PermissionRule(kind = NETWORK, effect = ALLOW), PermissionRule("fetch_url", null, DENY))
            .decide("fetch_url", s("https://x"), net, false) shouldBe DENY
        rules(PermissionRule("fetch_url", null, ALLOW), PermissionRule(kind = NETWORK, effect = DENY))
            .decide("fetch_url", s("https://x"), net, false) shouldBe DENY
    }

    test("kind rules load from permissions.json; an unknown kind is an error; saving old rules adds no keys") {
        val dir = createTempDirectory("kinds")
        val global = dir.resolve("g.json").also {
            it.writeText("""[{"kind":"network","effect":"deny","unattended":true},{"tool":"bash","pattern":"git *","effect":"allow"}]""")
        }
        val loaded = PermissionRules.load(global, dir.resolve("p.json"), dir.resolve("t.json"))
        loaded.active shouldBe listOf(PermissionRule(kind = NETWORK, effect = DENY, unattended = true), PermissionRule("bash", "git *", ALLOW))

        dir.resolve("bad.json").writeText("""[{"kind":"telepathy","effect":"deny"}]""")
        shouldThrow<PermissionRulesException> { PermissionRules.load(dir.resolve("bad.json"), dir.resolve("p.json"), dir.resolve("t.json")) }

        val project = dir.resolve("proj.json")
        PermissionRules.load(dir.resolve("none.json"), project, dir.resolve("t.json")).addProjectAllows(listOf(PermissionRule("todo", null, ALLOW)))
        project.readText().let { it shouldNotContain "kind"; it shouldNotContain "unattended" }
    }

    test("tightened keeps every outer rule, adds deny and ask, drops allow, and writes through to the outer rules") {
        val dir = createTempDirectory("tight")
        val outer = PermissionRules.load(dir.resolve("g.json"), dir.resolve("p.json"), dir.resolve("t.json"))
        outer.addProjectAllows(listOf(PermissionRule("bash", "ls", ALLOW)))
        val inner = outer.tightened(listOf(PermissionRule("bash", null, ALLOW), PermissionRule(kind = NETWORK, effect = DENY)))

        inner.decide("bash", s("ls"), setOf(PROCESS), false) shouldBe ALLOW
        inner.decide("bash", s("make"), setOf(PROCESS), false) shouldBe null // the inner allow-all was dropped
        inner.decide("fetch_url", s("https://x"), net, false) shouldBe DENY
        outer.decide("fetch_url", s("https://x"), net, false) shouldBe null

        inner.addProjectAllows(listOf(PermissionRule("bash", "pwd", ALLOW)))
        outer.decide("bash", s("pwd"), setOf(PROCESS), false) shouldBe ALLOW
        inner.decide("bash", s("pwd"), setOf(PROCESS), false) shouldBe ALLOW
    }

    test("why names the kind rule behind a decision, for a run's report") {
        val r = rules(PermissionRule(kind = NETWORK, effect = DENY, unattended = true), PermissionRule("bash", null, DENY))
        r.why(DENY, "fetch_url", net, unattended = true) shouldBe "network deny (unattended)"
        r.why(DENY, "bash", setOf(PROCESS), unattended = true) shouldBe null
    }
})
