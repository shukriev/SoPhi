package dev.sophi.cli

import dev.sophi.context.ContextEngine
import dev.sophi.context.GitProbe
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class ContextCommandTest : FunSpec({
    fun engine() = autoClose(ContextEngine(tempdir().toPath(), GitProbe.none, drainEveryMillis = 100).start()).also { it.awaitReady(10_000) shouldBe true }

    test("add registers a folder and list shows it") {
        val e = engine(); val out = mutableListOf<String>()
        val dir = tempdir().toPath()
        ContextAdd(e, dir) { out += it }.run()
        ContextList(e) { out += it }.run()
        out.last() shouldContain dir.toRealPath().fileName.toString()
    }

    test("cwd outside a repo is not auto-registered and prints one hint") {
        val e = engine(); val out = mutableListOf<String>()
        registerCwd(e, tempdir().toPath()) { out += it }
        e.projects() shouldHaveSize 0
        out shouldHaveSize 1
        out.single() shouldContain "sophi context add"
    }

    test("cwd inside a repo registers the repo root") {
        val e = engine()
        val repo = tempdir().toPath().also { Files.createDirectories(it.resolve(".git/x")); Files.createDirectories(it.resolve("a")) }
        registerCwd(e, repo.resolve("a")) { }
        e.projects().single().root shouldBe repo.toRealPath().toString()
    }

    test("remove accepts a directory") {
        val e = engine(); val dir = tempdir().toPath()
        ContextAdd(e, dir) { }.run()
        ContextRemove(e, dir.toString()) { }.run()
        e.projects() shouldHaveSize 0
    }

    test("list and remove on a never-started engine print the not-ready message") {
        val e = autoClose(ContextEngine(tempdir().toPath(), GitProbe.none)); val out = mutableListOf<String>()
        ContextList(e) { out += it }.run()
        ContextRemove(e, "x") { out += it }.run()
        out shouldBe listOf(NOT_READY_MSG, NOT_READY_MSG)
    }

    test("reset deletes the database when nothing hosts it") {
        val root = tempdir().toPath(); val out = mutableListOf<String>()
        val host = dev.sophi.store.arcade.ArcadeHost.open(root, ContextEngine.DATABASE, ContextEngine.SCHEMA_VERSION, "t")
        host.role shouldBe dev.sophi.store.arcade.Role.HOST
        // Same-JVM second open can't take the OS lock the way another process would; only assert the free case.
        host.close()
        ContextReset(root) { out += it }.run()
        out.single() shouldContain "deleted"
    }
})
