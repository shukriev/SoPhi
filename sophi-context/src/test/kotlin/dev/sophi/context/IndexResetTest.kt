package dev.sophi.context

import dev.sophi.store.arcade.ArcadeHost
import dev.sophi.store.arcade.Role
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class IndexResetTest : FunSpec({
    test("reset moves the database aside when nothing hosts it") {
        val root = tempdir().toPath()
        val host = ArcadeHost.open(root, ContextEngine.DATABASE, ContextEngine.SCHEMA_VERSION, "t")
        host.role shouldBe Role.HOST
        host.close()
        IndexReset(root).run() shouldBe ResetResult.Done
    }

    test("reset moves a corrupted index aside and a fresh engine rebuilds on that root") {
        val root = tempdir().toPath()
        ContextEngine(root, GitProbe.none, drainEveryMillis = 100).start().use { it.awaitReady(10_000) shouldBe true }
        val db = root.resolve("databases").resolve(ContextEngine.DATABASE)
        Files.walk(db).use { s -> s.filter { Files.isRegularFile(it) }.forEach { Files.write(it, ByteArray(4096) { i -> (i * 31 + 7).toByte() }) } }
        IndexReset(root).run() shouldBe ResetResult.Done
        Files.list(root).use { s -> s.anyMatch { it.fileName.toString().startsWith("context.corrupt-") } } shouldBe true
        val fresh = autoClose(ContextEngine(root, GitProbe.none, drainEveryMillis = 100).start())
        fresh.awaitReady(10_000) shouldBe true
        val repo = tempdir().toPath().also { Files.createDirectories(it.resolve(".git")); Files.writeString(it.resolve("a.kt"), "class Zebra") }
        val id = (fresh.register(repo, auto = true) as RegisterResult.Registered).project.id
        val end = System.currentTimeMillis() + 10_000
        while (fresh.search("zebra", listOf(id))?.isNotEmpty() != true) { check(System.currentTimeMillis() < end) { "timed out" }; Thread.sleep(50) }
    }

    test("reset is refused while another engine hosts the index") {
        val root = tempdir().toPath()
        autoClose(ContextEngine(root, GitProbe.none, drainEveryMillis = 100).start()).awaitReady(10_000) shouldBe true
        IndexReset(root).run() shouldBe ResetResult.InUse
    }
})
