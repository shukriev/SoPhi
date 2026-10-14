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

    test("reset maps the result to a message") {
        val out = mutableListOf<String>()
        ContextReset(tempdir().toPath()) { out += it }.run()
        out.single() shouldContain "moved aside"
    }

    test("global flags a project by directory and list shows it") {
        val e = engine(); val dir = tempdir().toPath(); val out = mutableListOf<String>()
        ContextAdd(e, dir) { }.run()
        ContextGlobal(e, dir.toString(), true) { out += it }.run()
        ContextList(e) { out += it }.run()
        out[0] shouldContain "now global"
        out[1] shouldContain " global"
    }

    test("add says it is indexed while a session runs, not that it is indexing now") {
        val e = engine(); val out = mutableListOf<String>()
        ContextAdd(e, tempdir().toPath()) { out += it }.run()
        out.single() shouldContain "indexed in the background while a Sophi session runs"
    }

    test("registerCwd re-requests a scan for a covered project that was never scanned") {
        val e = autoClose(ContextEngine(tempdir().toPath(), GitProbe.none, drainEveryMillis = 600_000).start()).also { it.awaitReady(10_000) shouldBe true }
        val repo = tempdir().toPath().also { Files.createDirectories(it.resolve(".git")); Files.writeString(it.resolve("a.kt"), "class Zebra") }
        val id = (e.register(repo, auto = true) as dev.sophi.context.RegisterResult.Registered).project.id
        e.withStoreOrNull { it.deleteDocument(dev.sophi.context.ContextSchema.REQUEST, id) }
        e.withStoreOrNull { it.documents(dev.sophi.context.ContextSchema.REQUEST) }!! shouldHaveSize 0
        registerCwd(e, repo) { }
        e.withStoreOrNull { it.documents(dev.sophi.context.ContextSchema.REQUEST) }!! shouldHaveSize 1
    }
})
