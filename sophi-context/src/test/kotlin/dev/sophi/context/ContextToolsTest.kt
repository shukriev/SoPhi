package dev.sophi.context

import dev.sophi.context.tools.contextTools
import dev.sophi.extensions.HookContext
import dev.sophi.extensions.HookPoint
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
        return Triple(e, id, r)
    }
    fun tool(e: ContextEngine, id: String, name: String) = contextTools(e) { listOf(id) }.first { it.name == name }

    test("search_context lists path, lines, label and a snippet") {
        val (e, id, _) = indexedRepo()
        val out = runBlocking { tool(e, id, "search_context").execute("""{"query":"compactor"}""") }
        out shouldStartWith "src/main/Compactor.kt:"
        out shouldContain "ContextCompactor"
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

    test("project_map shows name, languages, layout and key files with summaries") {
        val (e, id, _) = indexedRepo()
        val map = runBlocking { tool(e, id, "project_map").execute("{}") }
        map shouldContain "kotlin"
        map shouldContain "src/"
        map shouldContain "README.md — A demo project for tools."
    }

    test("tools answer not-ready when the engine has no store") {
        val e = ContextEngine(tempdir().toPath(), GitProbe.none)   // never started
        contextTools(e) { listOf("x") }.forEach { t ->
            runBlocking { t.execute("""{"query":"a","path":"a"}""") } shouldBe ContextEngine.NOT_READY
        }
        e.close()
    }

    test("a successful write_file, edit_file or bash requests a rescan") {
        val (e, id, r) = indexedRepo()
        Thread.sleep(5); r.write("src/main/New.kt", "class Zeppelin")
        val plugin = ContextPlugin(e) { listOf(id) }
        runBlocking { plugin.hooks().single { it.point == HookPoint.AFTER_TOOL }.invoke(HookContext("s", toolName = "write_file", success = true)) }
        eventually { e.search("zeppelin", listOf(id))?.isNotEmpty() == true }
    }
})
