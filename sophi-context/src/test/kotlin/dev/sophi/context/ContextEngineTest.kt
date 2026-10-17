package dev.sophi.context

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldNotBeNull
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
        val outer = tempdir().toPath()   // test-owned parent, not the system temp dir
        val r = outer.resolve("repo").also { Files.createDirectories(it.resolve(".git")); Files.createDirectories(it.resolve("a/b")) }
        val reg = e.register(r.resolve("a/b"), auto = true) as RegisterResult.Registered
        reg.project.root shouldBe r.toRealPath().toString()
        e.register(r.resolve("a"), auto = false).shouldBeInstanceOf<RegisterResult.AlreadyCovered>()
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

    test("a second engine in the same process shares the host and sees the first's registration") {
        val db = tempdir().toPath()
        val host = engine(db)
        val client = engine(db)
        val r = repo(); r.write("README.md", "# Widget\n\nThe widget explainer.\n")
        val p = (client.register(r, auto = true) as RegisterResult.Registered).project
        eventually { client.search("widget explainer", listOf(p.id))?.isNotEmpty() == true }
        host.status().role shouldBe "host"; client.status().role shouldBe "host"
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

    test("setGlobal flips the flag and a fresh read sees it") {
        val e = engine(tempdir().toPath())
        val id = (e.register(repo(), auto = false) as RegisterResult.Registered).project.id
        e.setGlobal(id, true) shouldBe true
        e.projects().first { it.id == id }.global shouldBe true
        e.setGlobal("nope", true) shouldBe false
    }

    test("status reports host role, projects and a positive disk size once a scan wrote data") {
        val e = engine(tempdir().toPath())
        e.register(repo().also { it.write("a.kt", "class Alpha") }, auto = false)
        eventually { e.projects().single().lastScanAt != null }
        val s = e.status()
        s.ready shouldBe true; s.role shouldBe "host"; s.projects.size shouldBe 1; (s.diskBytes > 0) shouldBe true
    }

    test("status of an engine that could not open carries the reason and no projects") {
        val e = autoClose(ContextEngine(tempdir().toPath().resolve("file-not-dir").also { Files.writeString(it, "x") }).start())
        e.awaitReady(5_000) shouldBe false
        val s = e.status()
        s.ready shouldBe false; s.reason.shouldNotBeNull(); s.projects shouldBe emptyList()
    }

    test("dirSize of a missing path is 0 and sums files of an existing one; status never throws") {
        val e = autoClose(ContextEngine(tempdir().toPath(), GitProbe.none))   // never started
        e.dirSize(tempdir().toPath().resolve("nope")) shouldBe 0L
        val d = tempdir().toPath().also { it.write("a/x.bin", "12345"); it.write("y.bin", "123") }
        e.dirSize(d) shouldBe 8L
        e.status().let { it.ready shouldBe false; it.diskBytes shouldBe 0L }
    }

    test("the host embeds chunks in the background and search fuses vectors; the hint path stays lexical") {
        val e = autoClose(ContextEngine(tempdir().toPath(), GitProbe.none, scanInterval = 60_000, drainEveryMillis = 100,
            embedder = ChunkEmbedder(FakeEmbeddings(), "fake")).start())
        e.awaitReady(10_000) shouldBe true
        val r = tempdir().toPath().also { Files.createDirectories(it.resolve(".git")) }
        Files.createDirectories(r.resolve("cars")); Files.writeString(r.resolve("cars/Garage.kt"), "class Garage { fun park(car: Car) {} }")
        val id = (e.register(r, auto = true) as RegisterResult.Registered).project.id
        val end = System.currentTimeMillis() + 10_000
        while (e.search("automobile", listOf(id)).orEmpty().isEmpty()) { check(System.currentTimeMillis() < end) { "timed out" }; Thread.sleep(100) }
        e.search("automobile", listOf(id))!!.first().path shouldBe "cars/Garage.kt"
        e.search("automobile", listOf(id), vectors = false) shouldBe emptyList()
    }
})
