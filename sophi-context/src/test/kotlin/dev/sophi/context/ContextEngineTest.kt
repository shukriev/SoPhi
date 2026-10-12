package dev.sophi.context

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

class ContextEngineTest : FunSpec({
    fun Path.write(rel: String, text: String) = resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, text) }
    fun repo(): Path = tempdir().toPath().also { Files.createDirectories(it.resolve(".git")) }
    fun engine(db: Path) = autoClose(ContextEngine(db, GitProbe.none, scanInterval = 0, drainEveryMillis = 100).start()).also { it.awaitReady(10_000) shouldBe true }
    fun eventually(ms: Long = 10_000, check: () -> Boolean) { val end = System.currentTimeMillis() + ms; while (!check()) { check(System.currentTimeMillis() < end) { "timed out" }; Thread.sleep(50) } }

    test("register refuses home and root; auto-register needs a repo or an instruction file") {
        val e = engine(tempdir().toPath())
        e.register(Path.of(System.getProperty("user.home")), auto = false).shouldBeInstanceOf<RegisterResult.Refused>()
        e.register(Path.of("/"), auto = false).shouldBeInstanceOf<RegisterResult.Refused>()
        e.register(tempdir().toPath(), auto = true).shouldBeInstanceOf<RegisterResult.Refused>()
        val withAgents = tempdir().toPath().also { it.write("AGENTS.md", "rules") }
        e.register(withAgents, auto = true).shouldBeInstanceOf<RegisterResult.Registered>()
    }

    test("a subfolder of a repo registers the repo root; nested registration is covered; a parent replaces the child") {
        val e = engine(tempdir().toPath())
        val r = repo(); Files.createDirectories(r.resolve("a/b"))
        val reg = e.register(r.resolve("a/b"), auto = true) as RegisterResult.Registered
        reg.project.root shouldBe r.toRealPath().toString()
        e.register(r.resolve("a"), auto = false).shouldBeInstanceOf<RegisterResult.AlreadyCovered>()
        val outer = r.parent
        val parent = e.register(outer, auto = false) as RegisterResult.Registered
        parent.replaced shouldBe listOf(reg.project.id)
        e.projects().map { it.id } shouldBe listOf(parent.project.id)
    }

    test("registered project is indexed by the host and searchable") {
        val e = engine(tempdir().toPath())
        val r = repo(); r.write("src/Compactor.kt", "class ContextCompactor { fun compact() {} }")
        val p = (e.register(r, auto = true) as RegisterResult.Registered).project
        eventually { e.search("compactor", listOf(p.id))?.isNotEmpty() == true }
        e.search("compactor", listOf(p.id))!!.first().path shouldBe "src/Compactor.kt"
    }

    test("client registration is indexed by the host") {
        val db = tempdir().toPath()
        val host = engine(db)
        val client = engine(db)
        val r = repo(); r.write("README.md", "# Widget\n\nThe widget explainer.\n")
        val p = (client.register(r, auto = true) as RegisterResult.Registered).project
        eventually { client.search("widget explainer", listOf(p.id))?.isNotEmpty() == true }
        host.projects().map { it.id } shouldBe listOf(p.id)
    }

    test("search returns null before the engine is ready, never throws") {
        val e = ContextEngine(tempdir().toPath(), GitProbe.none)   // not started
        e.search("anything", listOf("x")) shouldBe null
        e.ready shouldBe false
        e.close()
    }

    test("an edited file is picked up after the next requested scan") {
        val e = engine(tempdir().toPath())
        val r = repo(); val f = r.write("a.kt", "class Alpha")
        val p = (e.register(r, auto = true) as RegisterResult.Registered).project
        eventually { e.search("alpha", listOf(p.id))?.isNotEmpty() == true }
        Thread.sleep(5); Files.writeString(f, "class Omega")
        e.requestScan(p.id)
        eventually { e.search("omega", listOf(p.id))?.isNotEmpty() == true }
        eventually { e.search("alpha", listOf(p.id))?.isEmpty() == true }
    }

    test("re-registering right after remove keeps the project") {
        val e = engine(tempdir().toPath())
        val r = repo(); r.write("a.kt", "class Zeta")
        val p = (e.register(r, auto = true) as RegisterResult.Registered).project
        e.remove(p.id)
        e.register(r, auto = true).shouldBeInstanceOf<RegisterResult.Registered>()
        Thread.sleep(600)
        e.projects().map { it.id } shouldBe listOf(p.id)
        eventually { e.search("zeta", listOf(p.id))?.isNotEmpty() == true }
    }

    test("a folder containing the home folder is refused") {
        val e = engine(tempdir().toPath())
        val home = Path.of(System.getProperty("user.home")).toRealPath()
        e.register(home.parent, auto = false).shouldBeInstanceOf<RegisterResult.Refused>()
    }
})
