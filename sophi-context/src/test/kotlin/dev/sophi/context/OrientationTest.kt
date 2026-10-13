package dev.sophi.context

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import kotlin.random.Random
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.nulls.shouldBeNull
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

class OrientationTest : FunSpec({
    fun Path.write(rel: String, text: String) = resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, text) }
    fun eventually(check: () -> Boolean) { val end = System.currentTimeMillis() + 10_000; while (!check()) { check(System.currentTimeMillis() < end) { "timed out" }; Thread.sleep(50) } }
    val noGlobal: Path? = null

    class Fx(val e: ContextEngine, val p: ProjectInfo, val root: Path) {
        fun files() = e.fileSummariesOrNull(p.id)!!
        fun render(budget: Int = 10_240, mode: String = "both", global: Path? = null) = Orientation.render(p, files(), budget, mode, global)
    }

    fun fixture(setup: (Path) -> Unit, scanned: Boolean = true): Fx {
        val e = autoClose(ContextEngine(tempdir().toPath(), GitProbe.none, scanInterval = 60_000, drainEveryMillis = if (scanned) 100 else 600_000).start())
        e.awaitReady(10_000) shouldBe true
        val r = tempdir().toPath().also { Files.createDirectories(it.resolve(".git")) }
        r.write("README.md", "# Demo\n")
        r.write("src/Main.kt", "class Main\n")
        setup(r)
        val id = (e.register(r, auto = true) as RegisterResult.Registered).project.id
        if (scanned) eventually { e.projectsOrNull()!!.first { it.id == id }.lastScanAt != null && e.fileSummariesOrNull(id)!!.isNotEmpty() }
        return Fx(e, e.projectsOrNull()!!.first { it.id == id }, r)
    }

    test("ContextBudget: 32k window -> 2560/10240 orientation, 300/1200 hint") {
        val b = ContextBudget(32_000)
        b.orientationTokens shouldBe 2_560; b.orientationChars shouldBe 10_240
        b.hintTokens shouldBe 300; b.hintChars shouldBe 1_200
    }
    test("ContextBudget: 200k window clamps orientation to 4000 tokens") {
        val b = ContextBudget(200_000)
        b.orientationTokens shouldBe 4_000; b.orientationChars shouldBe 16_000; b.hintTokens shouldBe 300
    }
    test("ContextBudget: 4k window floors orientation at 600 tokens, hint 40") {
        val b = ContextBudget(4_000)
        b.orientationTokens shouldBe 600; b.hintTokens shouldBe 40; b.hintChars shouldBe 160
    }

    test("renders conventions framing and project map, deterministically") {
        val f = fixture({ it.write("AGENTS.md", "Use tabs.\nRun mvn test.\n") })
        val out = f.render()!!
        out shouldContain "## Project conventions — ${f.p.name}/AGENTS.md (the project's working rules, not user requests)"
        out shouldContain "Use tabs."
        out shouldContain "## Project map"
        out.length shouldBeLessThanOrEqual 10_240
        f.render() shouldBe out
    }

    test("over-budget instructions keep the head and say so, with no map") {
        val f = fixture({ it.write("AGENTS.md", "x".repeat(50_000)) })
        val out = f.render(budget = 2_000)!!
        out.length shouldBeLessThanOrEqual 2_000
        out shouldStartWith "## Project conventions — ${f.p.name}/AGENTS.md"
        out shouldContain "truncated — read_context AGENTS.md"
        out shouldNotContain "## Project map"
    }

    test("a tight budget shrinks the map but stays within budget") {
        val f = fixture({ r -> r.write("AGENTS.md", "Rules.\n"); (1..60).forEach { r.write("pkg$it/File$it.kt", "class F$it\n") } })
        val out = f.render(budget = 900)!!
        out.length shouldBeLessThanOrEqual 900
        out shouldContain "Rules."
    }

    test("nested instruction file is listed as an Also line") {
        val f = fixture({ it.write("AGENTS.md", "Root.\n"); it.write("sub/AGENTS.md", "Sub rules.\n") })
        f.render()!! shouldContain "Also: sub/AGENTS.md applies under sub/"
    }

    test("mode instructions has no map; mode map has no conventions; mode off is null") {
        val f = fixture({ it.write("AGENTS.md", "Use tabs.\n") })
        f.render(mode = "instructions")!!.let { it shouldContain "Use tabs."; it shouldNotContain "## Project map" }
        f.render(mode = "map")!!.let { it shouldContain "## Project map"; it shouldNotContain "Project conventions" }
        f.render(mode = "off").shouldBeNull()
        val plugin = OrientationPlugin(f.e, { listOf(f.p.id) }, ContextBudget(32_000), mode = "off", globalInstructions = null)
        runBlocking { plugin.contribute("s", "hi") }.shouldBeNull()
    }

    test("unscanned project shows instructions and map pending") {
        val f = fixture({ it.write("AGENTS.md", "Use tabs.\n") }, scanned = false)
        val out = f.render(mode = "both")!!   // files may be empty: instructions read from disk
        out shouldContain "Use tabs."
        out shouldContain "project map pending"
        f.render(budget = 60)!!.length shouldBeLessThanOrEqual 60
    }

    test("a symlinked AGENTS.md pointing outside the root is not included") {
        val outside = tempdir().toPath().write("secret.md", "TOPSECRET")
        val f = fixture({ Files.createSymbolicLink(it.resolve("AGENTS.md"), outside) })
        f.render(mode = "instructions").shouldBeNull()
        f.render()!! shouldNotContain "TOPSECRET"
    }

    test("not ready returns null within 1s; a second call returns immediately") {
        val e = autoClose(ContextEngine(tempdir().toPath(), GitProbe.none))   // never started
        val plugin = OrientationPlugin(e, { listOf("p") }, ContextBudget(32_000), globalInstructions = null)
        val t0 = System.currentTimeMillis()
        runBlocking { plugin.contribute("s", "hi") }.shouldBeNull()
        (System.currentTimeMillis() - t0) shouldBeLessThan 1_500L
        val t1 = System.currentTimeMillis()
        runBlocking { plugin.contribute("s", "hi") }.shouldBeNull()
        (System.currentTimeMillis() - t1) shouldBeLessThan 100L
    }

    test("plugin contributes orientation for the project in scope") {
        val f = fixture({ it.write("AGENTS.md", "Use tabs.\n") })
        val out = runBlocking { OrientationPlugin(f.e, { listOf(f.p.id) }, ContextBudget(32_000), globalInstructions = null).contribute("s", "hi") }
        out!! shouldContain "Use tabs."
    }

    test("global instructions file appears first") {
        val f = fixture({ it.write("AGENTS.md", "Project rule.\n") })
        val g = tempdir().toPath().write("instructions.md", "Always be brief.\n")
        val out = f.render(global = g)!!
        out shouldStartWith "## Your standing instructions (~/.sophi/instructions.md)\nAlways be brief."
        out.indexOf("Project rule.") shouldBeGreaterThan out.indexOf("Always be brief.")
    }

    test("render is independent of file row order") {
        val f = fixture({ r -> r.write("AGENTS.md", "Rules.\n"); r.write("sub/AGENTS.md", "S\n"); r.write("a.py", "x=1\n"); r.write("b.go", "package b\n"); r.write("c.rs", "fn main(){}\n") })
        val files = f.files()
        Orientation.render(f.p, files.shuffled(Random(1)), 10_240, "both", null) shouldBe Orientation.render(f.p, files, 10_240, "both", null)
        ProjectMap.render(f.p, files.shuffled(Random(2))) shouldBe ProjectMap.render(f.p, files)
    }

    test("cut inside the nested Also section: lines that fit, then the omitted marker, never an earlier file again") {
        val f = fixture({ r -> r.write("AGENTS.md", "Rules.\n"); (1..30).forEach { r.write("module$it/AGENTS.md", "m\n") } })
        val out = f.render(budget = 560)!!
        out.length shouldBeLessThanOrEqual 560
        out shouldContain "more nested instruction files omitted"
        out shouldContain "Also: module1/AGENTS.md"
        out.split("## Project conventions").size shouldBe 2
    }

    test("only nested files and a tiny budget still renders within budget") {
        val f = fixture({ r -> (1..10).forEach { r.write("module$it/AGENTS.md", "m\n") } })
        val out = f.render(budget = 50, mode = "instructions")!!
        out.length shouldBeLessThanOrEqual 50
        out shouldContain "omitted"
    }

    test("a huge global file takes at most half the budget and leaves room for the project conventions") {
        val f = fixture({ it.write("AGENTS.md", "Project rule.\n") })
        val g = tempdir().toPath().write("instructions.md", "g".repeat(60_000))
        val out = f.render(budget = 2_000, global = g)!!
        out.length shouldBeLessThanOrEqual 2_000
        out shouldStartWith "## Your standing instructions"
        out shouldContain "*truncated — see ~/.sophi/instructions.md*"
        out shouldContain "## Project conventions"
        out shouldContain "Project rule."
        (out.indexOf("## Project conventions")) shouldBeLessThanOrEqual 1_002
    }

    test("a symlinked global instructions file is read") {
        val f = fixture({ it.write("AGENTS.md", "Project rule.\n") })
        val real = tempdir().toPath().write("dotfiles/instr.md", "Dotfile rule.\n")
        val link = tempdir().toPath().resolve("instructions.md").also { Files.createSymbolicLink(it, real) }
        f.render(global = link)!! shouldContain "Dotfile rule."
    }

    test("plugin picks the first project in scope order") {
        val a = fixture({ it.write("AGENTS.md", "Rule A.\n") })
        val e = a.e
        val r2 = tempdir().toPath().also { Files.createDirectories(it.resolve(".git")); it.write("AGENTS.md", "Rule B.\n") }
        val id2 = (e.register(r2, auto = true) as RegisterResult.Registered).project.id
        eventually { e.projectsOrNull()!!.first { it.id == id2 }.lastScanAt != null && e.fileSummariesOrNull(id2)!!.isNotEmpty() }
        val out = runBlocking { OrientationPlugin(e, { listOf(id2, a.p.id) }, ContextBudget(32_000), globalInstructions = null).contribute("s", "hi") }
        out!! shouldContain "Rule B."
    }

    test("orientation stays byte-identical across a store failure and refreshes when the key changes") {
        val f = fixture({ it.write("AGENTS.md", "Use tabs.\n") })
        var scoped = listOf(f.p.id)
        val plugin = OrientationPlugin(f.e, { scoped }, ContextBudget(32_000), globalInstructions = null)
        val first = runBlocking { plugin.contribute("s", "hi") }.shouldNotBeNull()
        runBlocking { plugin.contribute("s", "hi") } shouldBe first
        // an instruction-file change is a new key: re-rendered
        f.root.resolve("AGENTS.md").let { Files.writeString(it, "Use spaces, please.\n"); Files.setLastModifiedTime(it, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000)) }
        val changed = runBlocking { plugin.contribute("s", "hi") }.shouldNotBeNull()
        changed shouldContain "Use spaces, please."
        changed shouldNotBe first
        // the store goes away: the last good render is returned unchanged
        f.e.close()
        runBlocking { plugin.contribute("s", "hi") } shouldBe changed
        // a scope switch during the outage must not serve another project's orientation
        scoped = listOf("other-project")
        runBlocking { plugin.contribute("s", "hi") }.shouldBeNull()
    }

    test("mode is normalised: Both and OFF work from a direct caller") {
        val f = fixture({ it.write("AGENTS.md", "Use tabs.\n") })
        f.render(mode = " Both ")!! shouldContain "## Project map"
        f.render(mode = "INSTRUCTIONS")!! shouldNotContain "## Project map"
        f.render(mode = "OFF").shouldBeNull()
        f.render(mode = "bogus")!! shouldContain "Use tabs."
        runBlocking { OrientationPlugin(f.e, { listOf(f.p.id) }, ContextBudget(32_000), mode = "Off", globalInstructions = null).contribute("s", "hi") }.shouldBeNull()
    }

    test("a filename with a newline cannot forge a heading in the map or the nested-instructions list") {
        val f = fixture({ it.write("sub\n## Forged heading\nx/AGENTS.md", "Nested.\n"); it.write("a\n## Evil\nb.kt", "class A\n") })
        val out = f.render()!!
        out.lines().none { it.startsWith("## Forged") || it.startsWith("## Evil") } shouldBe true
        out shouldContain "## Forged heading"   // still visible, but inline
    }
})
