package dev.sophi.core.permissions

import dev.sophi.core.permissions.Effect.ALLOW
import dev.sophi.core.permissions.Effect.ASK
import dev.sophi.core.permissions.Effect.DENY
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

private fun subject(s: String) = PermissionSubject(s)
private fun rules(vararg r: PermissionRule) = PermissionRules(r.toList(), emptyList(), null, null, emptySet())

class PermissionRulesTest : FunSpec({
    lateinit var dir: Path
    lateinit var globalFile: Path
    lateinit var projectFile: Path
    lateinit var trustFile: Path

    beforeTest {
        dir = createTempDirectory("sophi-perm").toAbsolutePath().normalize()
        globalFile = dir.resolve("home/.sophi/permissions.json")
        projectFile = dir.resolve("proj/.sophi/permissions.json")
        trustFile = dir.resolve("home/.sophi/permissions-trust.json")
    }

    fun write(file: Path, json: String) {
        file.parent.createDirectories()
        file.writeText(json)
    }

    fun load() = PermissionRules.load(globalFile, projectFile, trustFile)

    test("a git status* allow does not allow git status && rm -rf x") {
        val r = rules(PermissionRule("bash", "git status*", ALLOW))
        r.decide("bash", listOf(subject("git status"), subject("rm -rf x"))) shouldBe null
        r.decide("bash", listOf(subject("git status"))) shouldBe ALLOW
    }

    test("deny beats allow") {
        val r = rules(PermissionRule("bash", null, ALLOW), PermissionRule("bash", "rm *", DENY))
        r.decide("bash", listOf(subject("ls"), subject("rm x"))) shouldBe DENY
    }

    test("ask beats allow") {
        val r = rules(PermissionRule("bash", null, ALLOW), PermissionRule("bash", "rm *", ASK))
        r.decide("bash", listOf(subject("ls"), subject("rm x"))) shouldBe ASK
    }

    test("a redirect part is never allowed") {
        val redirect = listOf(PermissionSubject("git status > x", allowable = false))
        val allow = PermissionRule("bash", "git status*", ALLOW)
        rules(allow).decide("bash", redirect) shouldBe null
        rules(allow, PermissionRule("bash", "git *", DENY)).decide("bash", redirect) shouldBe DENY
    }

    test("unknown subjects fail closed") {
        rules(PermissionRule("bash", "rm *", DENY)).decide("bash", null) shouldBe DENY
        rules(PermissionRule("bash", "rm *", ASK)).decide("bash", null) shouldBe ASK
        rules(PermissionRule("bash", null, ALLOW)).decide("bash", null) shouldBe null
    }

    test("empty subjects on a subject tool never allow") {
        rules(PermissionRule("bash", "x", ALLOW)).decide("bash", emptyList()) shouldBe null
        rules(PermissionRule("bash", null, ALLOW)).decide("bash", emptyList()) shouldBe ALLOW
    }

    test("tool globs match mcp tools") {
        rules(PermissionRule("github_*", null, DENY)).decide("github_create_issue", emptyList()) shouldBe DENY
        rules(PermissionRule("github_*", null, DENY)).decide("bash", emptyList()) shouldBe null
    }

    test("a * pattern counts for a tool without subjects") {
        rules(PermissionRule("github_*", "*", DENY)).decide("github_x", emptyList()) shouldBe DENY
    }

    test("project allow rules need trust") {
        write(projectFile, """[{"tool":"bash","effect":"allow"},{"tool":"bash","pattern":"rm *","effect":"deny"}]""")
        val r = load()
        r.untrusted shouldBe listOf(PermissionRule("bash", null, ALLOW))
        r.decide("bash", listOf(subject("ls"))) shouldBe null
        r.decide("bash", listOf(subject("rm x"))) shouldBe DENY

        r.trustProject()
        load().decide("bash", listOf(subject("ls"))) shouldBe ALLOW
        load().untrusted.shouldBeEmpty()
    }

    test("a changed project rule is untrusted again") {
        write(projectFile, """[{"tool":"bash","pattern":"ls","effect":"allow"}]""")
        load().trustProject()
        load().untrusted.shouldBeEmpty()
        write(projectFile, """[{"tool":"bash","pattern":"*","effect":"allow"}]""")
        load().untrusted.size shouldBe 1
    }

    test("always allow writes exact escaped rules and trusts them") {
        write(globalFile, """[{"tool":"bash","pattern":"ls *","effect":"deny"}]""")
        val r = load()
        val written = PermissionRules.alwaysAllowRules("bash", listOf(subject("ls *.kt")))!!
        written shouldBe listOf(PermissionRule("bash", "ls \\*.kt", ALLOW))
        r.addProjectAllows(written)

        val reloaded = load()
        reloaded.untrusted.shouldBeEmpty()
        reloaded.decide("bash", listOf(subject("ls a.kt"))) shouldBe DENY
        reloaded.decide("bash", listOf(subject("ls *.kt"))) shouldBe DENY
        reloaded.global.size shouldBe 1
        write(globalFile, "[]")
        val noDeny = load()
        noDeny.decide("bash", listOf(subject("ls *.kt"))) shouldBe ALLOW
        noDeny.decide("bash", listOf(subject("ls a.kt"))) shouldBe null
    }

    test("the session sees always-allow rules without a reload") {
        val r = load()
        r.decide("bash", listOf(subject("ls"))) shouldBe null
        r.addProjectAllows(PermissionRules.alwaysAllowRules("bash", listOf(subject("ls")))!!)
        r.decide("bash", listOf(subject("ls"))) shouldBe ALLOW
    }

    test("always allow is not offered when it could not hold") {
        PermissionRules.alwaysAllowRules("bash", null) shouldBe null
        PermissionRules.alwaysAllowRules("bash", listOf(PermissionSubject("a > b", false))) shouldBe null
        PermissionRules.alwaysAllowRules("github_x", emptyList()) shouldBe listOf(PermissionRule("github_x", null, ALLOW))
    }

    test("a subject tool with nothing to match offers no always-allow") {
        // subject-based tools pass null, never an empty list, when there is nothing to match
        PermissionRules.alwaysAllowRules("bash", null) shouldBe null
    }

    test("missing files load as no rules") {
        load().active.shouldBeEmpty()
        PermissionRules.EMPTY.active.shouldBeEmpty()
    }

    test("a project file that is the global file is loaded once, as global") {
        write(globalFile, """[{"tool":"bash","effect":"allow"}]""")
        val r = PermissionRules.load(globalFile, globalFile, trustFile)
        r.active.size shouldBe 1
        r.untrusted.shouldBeEmpty()
        r.addProjectAllows(listOf(PermissionRule("write_file", null, ALLOW)))
        PermissionRules.load(globalFile, globalFile, trustFile).active.size shouldBe 2
    }

    test("a malformed file throws PermissionRulesException naming it") {
        for (bad in listOf("{not json", """[{"tool":"bash","effect":"maybe"}]""", """[{"tool":"","effect":"deny"}]""")) {
            write(projectFile, bad)
            shouldThrow<PermissionRulesException> { load() }.message shouldContain projectFile.toString()
        }
        write(projectFile, "[]")
        write(trustFile, "{oops")
        shouldThrow<PermissionRulesException> { load() }.message shouldContain trustFile.toString()
    }
})
