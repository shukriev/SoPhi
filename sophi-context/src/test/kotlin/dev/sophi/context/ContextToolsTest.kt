package dev.sophi.context

import dev.sophi.context.tools.contextTools
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import dev.sophi.core.session.SessionIdContext
import kotlinx.coroutines.withContext
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

class ContextToolsTest : FunSpec({
    fun Path.write(rel: String, text: String) = resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, text) }
    fun eventually(check: () -> Boolean) { val end = System.currentTimeMillis() + 10_000; while (!check()) { check(System.currentTimeMillis() < end) { "timed out" }; Thread.sleep(50) } }

    fun indexedRepo(): Triple<ContextEngine, String, Path> {
        val e = autoClose(ContextEngine(tempdir().toPath(), GitProbe.none, scanInterval = 60_000, drainEveryMillis = 100).start())
        e.awaitReady(10_000) shouldBe true
        val r = tempdir().toPath().also { Files.createDirectories(it.resolve(".git")) }
        r.write("README.md", "# Demo\n\nA demo project for tools.\n")
        r.write("src/main/Compactor.kt", "/** Summarises old rounds. */\nclass ContextCompactor {\n  fun compact() = 1\n}\n")
        r.write(".env", "SECRET=1")
        Files.writeString(r.resolve(".gitignore"), ".env\n")
        val id = (e.register(r, auto = true) as RegisterResult.Registered).project.id
        eventually { e.search("compactor", listOf(id))?.isNotEmpty() == true }
        // search() queues a rescan while lastScanAt is still null (hits land per file, lastScanAt at the end),
        // so wait for the first scan to finish and any queued rescan to drain before a test writes new files.
        eventually { e.projectsOrNull()!!.first { it.id == id }.lastScanAt != null }
        Thread.sleep(500)
        return Triple(e, id, r)
    }
    fun tool(e: ContextEngine, id: String, name: String) = contextTools(e, { id }).first { it.name == name }

    fun graphRepo(): Triple<ContextEngine, String, Path> {
        val e = autoClose(ContextEngine(tempdir().toPath(), GitProbe.none, scanInterval = 60_000, drainEveryMillis = 100).start())
        e.awaitReady(10_000) shouldBe true
        val r = tempdir().toPath().also { Files.createDirectories(it.resolve(".git")) }
        r.write("src/main/kotlin/demo/HubServer.kt", "package demo\n\nimport demo.Ports\n\n/** Serves the hub. */\nclass HubServer(val port: Int = Ports.HUB)\n")
        r.write("src/main/kotlin/demo/Ports.kt", "package demo\n\nobject Ports { const val HUB = 8765 }\n")
        r.write("src/test/kotlin/demo/HubServerTest.kt", "package demo\n\nclass HubServerTest { val s = HubServer() }\n")
        r.write("docs/hub.md", "# Hub\n\nSee [[ports]] and the [server notes](server.md).\n")
        r.write("docs/ports.md", "# Ports\n\nThe hub uses Ports.HUB.\n")
        r.write("docs/server.md", "# Server\n\nHubServer starts on boot.\n")
        val id = (e.register(r, auto = true) as RegisterResult.Registered).project.id
        eventually { e.projectsOrNull()!!.first { it.id == id }.lastScanAt != null }
        Thread.sleep(500)
        return Triple(e, id, r)
    }

    test("find_symbol: exact, case-insensitive and prefix definitions, plus mentions outside the definition file") {
        val (e, id, _) = graphRepo()
        val exact = runBlocking { tool(e, id, "find_symbol").execute("""{"name":"HubServer"}""") }
        exact shouldContain "src/main/kotlin/demo/HubServer.kt:6 class HubServer"
        exact shouldContain "HubServerTest.kt"
        exact.substringAfter("mentions:") shouldNotContain "demo/HubServer.kt:"
        runBlocking { tool(e, id, "find_symbol").execute("""{"name":"hubserver"}""") } shouldContain ":6 class HubServer"
        val prefix = runBlocking { tool(e, id, "find_symbol").execute("""{"name":"HubServ"}""") }
        prefix shouldContain "class HubServer"
        prefix shouldContain "class HubServerTest"
    }

    test("find_symbol with SQL-looking, empty or unknown input answers plainly and leaves the index working") {
        val (e, id, _) = graphRepo()
        val t = tool(e, id, "find_symbol")
        runBlocking { t.execute("""{"name":"x'); DROP TYPE Symbol"}""") } shouldNotContain "context tool failed"
        runBlocking { t.execute("""{"name":""}""") } shouldBe "invalid arguments"
        runBlocking { t.execute("""{"name":"Zebra"}""") } shouldStartWith "No definition or mention of Zebra"
        runBlocking { t.execute("""{"name":"Ports"}""") } shouldContain "object Ports"
    }

    test("find_symbol caps at 10 and a source definition beats test-file definitions the cap would cut") {
        val e = autoClose(ContextEngine(tempdir().toPath(), GitProbe.none, scanInterval = 60_000, drainEveryMillis = 100).start())
        e.awaitReady(10_000) shouldBe true
        val r = tempdir().toPath().also { Files.createDirectories(it.resolve(".git")) }
        // test paths sort before "zsrc", so only the non-test-first rule keeps the source definition inside the cap
        (1..12).forEach { r.write("src/test/kotlin/a$it/WidgetTest.kt", "package a$it\n\nclass Widget\n") }
        r.write("zsrc/main/kotlin/Widget.kt", "package z\n\nclass Widget\n")
        val id = (e.register(r, auto = true) as RegisterResult.Registered).project.id
        eventually { e.projectsOrNull()!!.first { it.id == id }.lastScanAt != null }
        Thread.sleep(500)
        val out = runBlocking { tool(e, id, "find_symbol").execute("""{"name":"Widget"}""") }
        val defs = out.substringAfter("definitions:").substringBefore("mentions:").lines().filter { it.isNotBlank() }
        defs.size shouldBe 10
        defs.first() shouldContain "zsrc/main/kotlin/Widget.kt:3 class Widget"
    }

    test("related: imports, imported by, tested by, links and backlinks") {
        val (e, id, _) = graphRepo()
        val rel = tool(e, id, "related")
        val hub = runBlocking { rel.execute("""{"path":"src/main/kotlin/demo/HubServer.kt"}""") }
        hub shouldContain "imports: src/main/kotlin/demo/Ports.kt"
        hub shouldContain "tested by: src/test/kotlin/demo/HubServerTest.kt"
        runBlocking { rel.execute("""{"path":"src/main/kotlin/demo/Ports.kt"}""") } shouldContain "imported by: src/main/kotlin/demo/HubServer.kt"
        runBlocking { rel.execute("""{"path":"docs/hub.md"}""") } shouldContain "links: docs/ports.md, docs/server.md"
        runBlocking { rel.execute("""{"path":"docs/server.md"}""") } shouldContain "backlinks: docs/hub.md"
    }

    test("related refuses paths outside the index") {
        val (e, id, _) = graphRepo()
        runBlocking { tool(e, id, "related").execute("""{"path":"../../etc/passwd"}""") } shouldBe "not an indexed file in this project: ../../etc/passwd"
        runBlocking { tool(e, id, "related").execute("""{"path":"nope.kt"}""") } shouldBe "not an indexed file in this project: nope.kt"
    }

    test("related with no project in scope says so") {
        val e = autoClose(ContextEngine(tempdir().toPath(), GitProbe.none).start())
        e.awaitReady(10_000) shouldBe true
        runBlocking { contextTools(e, { null }).first { it.name == "related" }.execute("""{"path":"a.kt"}""") } shouldStartWith "No indexed project for this session"
    }

    test("related says so when the project's relations are being rebuilt") {
        val (e, id, _) = graphRepo()
        e.withStoreOrNull { it.command("UPDATE ${ContextSchema.PROJECT} SET relationsAt = null WHERE id = ?", id) }
        runBlocking { tool(e, id, "related").execute("""{"path":"src/main/kotlin/demo/HubServer.kt"}""") } shouldContain "relations are being rebuilt — results may be incomplete"
    }

    test("tool surface: all = 5, core = search + read, none = 0") {
        val e = ContextEngine(tempdir().toPath(), GitProbe.none)
        contextTools(e, { null }).map { it.name } shouldBe listOf("search_context", "read_context", "project_map", "find_symbol", "related")
        contextTools(e, { null }, "core").map { it.name } shouldBe listOf("search_context", "read_context")
        contextTools(e, { null }, "none") shouldBe emptyList()
    }

    test("search_context lists path, lines, label and a snippet") {
        val (e, id, _) = indexedRepo()
        val out = runBlocking { tool(e, id, "search_context").execute("""{"query":"compactor"}""") }
        out shouldStartWith "src/main/Compactor.kt:"
        out shouldContain "ContextCompactor"
    }

    test("a registered but unscanned project reads as indexing, not empty") {
        val e = autoClose(ContextEngine(tempdir().toPath(), GitProbe.none, drainEveryMillis = 600_000).start())
        e.awaitReady(10_000) shouldBe true
        val r = tempdir().toPath().also { Files.createDirectories(it.resolve(".git")) }
        r.write("README.md", "# Demo\n")
        val id = (e.register(r, auto = true) as RegisterResult.Registered).project.id
        val name = r.toRealPath().fileName.toString()
        runBlocking { tool(e, id, "search_context").execute("""{"query":"demo"}""") } shouldBe "indexing $name — results incomplete; use grep/glob/read meanwhile"
        runBlocking { tool(e, id, "project_map").execute("{}") } shouldStartWith "indexing $name — results incomplete"
    }

    test("read_context returns numbered lines of an indexed file") {
        val (e, id, _) = indexedRepo()
        runBlocking { tool(e, id, "read_context").execute("""{"path":"src/main/Compactor.kt","startLine":2,"endLine":2}""") } shouldBe "2: class ContextCompactor {"
    }

    test("read_context refuses unindexed and escaping paths") {
        val (e, id, _) = indexedRepo()
        val read = tool(e, id, "read_context")
        runBlocking { read.execute("""{"path":".env"}""") } shouldStartWith "not an indexed file"
        runBlocking { read.execute("""{"path":"../../etc/passwd"}""") } shouldStartWith "not an indexed file"
        runBlocking { read.execute("""{"path":"/etc/passwd"}""") } shouldStartWith "not an indexed file"
    }

    test("read_context on a file deleted after indexing returns a message, not an exception") {
        val (e, id, r) = indexedRepo()
        Files.delete(r.resolve("README.md"))
        runBlocking { tool(e, id, "read_context").execute("""{"path":"README.md"}""") } shouldStartWith "not an indexed file"
    }

    test("read_context refuses a file replaced by a symlink to outside the root") {
        val (e, id, r) = indexedRepo()
        val outside = tempdir().toPath().write("secret.txt", "TOPSECRET")
        Files.delete(r.resolve("README.md")); Files.createSymbolicLink(r.resolve("README.md"), outside)
        runBlocking { tool(e, id, "read_context").execute("""{"path":"README.md"}""") } shouldStartWith "not an indexed file"
    }

    test("read_context refuses a directory replaced by a symlink to outside the root") {
        val (e, id, r) = indexedRepo()
        r.write("sub/x.kt", "class X")
        e.requestScan(id)
        eventually { e.fileIndexed(id, "sub/x.kt") }
        val outside = tempdir().toPath(); outside.write("x.kt", "TOPSECRET")
        r.resolve("sub").toFile().deleteRecursively(); Files.createSymbolicLink(r.resolve("sub"), outside)
        runBlocking { tool(e, id, "read_context").execute("""{"path":"sub/x.kt"}""") } shouldStartWith "not an indexed file"
    }

    test("read_context reports the line count for an out-of-range request") {
        val (e, id, _) = indexedRepo()
        val read = tool(e, id, "read_context")
        runBlocking { read.execute("""{"path":"src/main/Compactor.kt","startLine":99}""") } shouldBe "src/main/Compactor.kt has 4 lines"
        runBlocking { read.execute("""{"path":"src/main/Compactor.kt","startLine":3,"endLine":2}""") } shouldBe "src/main/Compactor.kt has 4 lines"
    }

    test("project_map shows name, languages, layout and key files with summaries") {
        val (e, id, _) = indexedRepo()
        val map = runBlocking { tool(e, id, "project_map").execute("{}") }
        map shouldContain "kotlin"
        map shouldContain "src/"
        map shouldContain "README.md — A demo project for tools."
    }

    test("tools answer not-ready when the engine has no store") {
        val e = ContextEngine(tempdir().toPath(), GitProbe.none)   // never started
        contextTools(e, { "x" }).forEach { t ->
            runBlocking { t.execute("""{"query":"a","path":"a"}""") } shouldBe ContextEngine.NOT_READY
        }
        e.close()
    }

    test("tools search the session's project plus globals, read from the coroutine's session id") {
        val (e, a, _) = indexedRepo()
        val g = tempdir().toPath().also { it.write("notes/beta.md", "# Beta gardening\n\nTomatoes and basil.\n") }
        val gid = (e.register(g, auto = false) as RegisterResult.Registered).project.id
        e.withStoreOrNull { it.command("UPDATE ${ContextSchema.PROJECT} SET isGlobal = true WHERE id = ?", gid) }
        eventually { e.search("gardening", listOf(gid))?.isNotEmpty() == true }
        val search = contextTools(e, { sid -> if (sid == "s-a") a else null }).first { it.name == "search_context" }
        runBlocking { withContext(SessionIdContext("s-a")) { search.execute("""{"query":"compactor"}""") } } shouldContain "src/main/Compactor.kt"
        runBlocking { withContext(SessionIdContext("s-none")) { search.execute("""{"query":"compactor"}""") } } shouldNotContain "Compactor.kt"
        runBlocking { withContext(SessionIdContext("s-none")) { search.execute("""{"query":"gardening"}""") } } shouldContain "beta.md"
    }

    test("a successful write_file, edit_file or bash requests a rescan") {
        val (e, id, r) = indexedRepo()
        Thread.sleep(5); r.write("src/main/New.kt", "class Zeppelin")
        val plugin = ContextPlugin(e) { id }
        runBlocking { plugin.hooks().single { it.point == HookPoint.AFTER_TOOL }.invoke(HookContext("s", toolName = "write_file", success = true)) }
        eventually { e.search("zeppelin", listOf(id))?.isNotEmpty() == true }
    }

    test("a failed write_file or a successful read_file requests no rescan") {
        val (e, id, r) = indexedRepo()
        r.write("src/main/New.kt", "class Zeppelin")
        val hook = ContextPlugin(e) { id }.hooks().single { it.point == HookPoint.AFTER_TOOL }
        runBlocking {
            hook.invoke(HookContext("s", toolName = "write_file", success = false))
            hook.invoke(HookContext("s", toolName = "read_file", success = true))
        }
        Thread.sleep(1_500)   // many drain cycles; scanInterval is 60s so nothing else rescans
        e.search("zeppelin", listOf(id))?.isEmpty() shouldBe true
    }
})
