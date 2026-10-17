package dev.sophi.memory.jane

import dev.sophi.memory.BrowseFilter
import dev.sophi.memory.FakeEmbeddingProvider
import dev.sophi.store.arcade.ArcadeHost
import dev.sophi.store.arcade.IncompatibleHostException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

class JanesPalaceSharedTest : FunSpec({
    fun config(home: Path, root: Path) = JanesPalaceConfig(home = home, sharedRoot = root)

    test("only the default home moves to the shared store") {
        JanesPalaceConfig.sharedRootFor(JanesPalaceConfig.DEFAULT_HOME) shouldBe ArcadeHost.defaultRoot()
        JanesPalaceConfig.sharedRootFor(tempdir().toPath()) shouldBe null
    }

    test("an existing embedded palace moves to the shared store once and is still recalled") {
        val home = tempdir().toPath(); val root = tempdir().toPath()
        val v = runBlocking { FakeEmbeddingProvider().embed(listOf("dentist appointment friday")) }.first()
        LegacyPalaceFixture.write(home, listOf("m1" to "dentist appointment friday", "m2" to "likes green tea"), vectors = mapOf("m1" to v), edge = "m1" to "m2")
        Files.writeString(home.resolve("audit.jsonl"), "{\"kept\":true}\n")
        val palace = JanesPalace(config(home, root), null, FakeEmbeddingProvider())
        try {
            palace.browse(BrowseFilter()).map { it.id }.toSet() shouldBe setOf("m1", "m2")
            runBlocking { palace.search("dentist appointment friday", 1) }.map { it.id } shouldBe listOf("m1")
        } finally { palace.close() }
        Files.exists(home.resolve("arcadedb")) shouldBe false
        Files.list(home).use { s -> s.anyMatch { it.fileName.toString().startsWith("arcadedb.migrated-") } } shouldBe true
        Files.readString(home.resolve("audit.jsonl")) shouldContain "kept"
        val again = JanesPalace(config(home, root), null, FakeEmbeddingProvider())
        try { again.browse(BrowseFilter()).size shouldBe 2 } finally { again.close() }
        // Vectors, edges and documents all made it across; the old directory is kept, not emptied.
        val moved = PalaceStore.shared(home, root)
        try {
            moved.vectorFor("m1")!!.toList() shouldBe v.toList()
            moved.vectorFor("m2") shouldBe null
            moved.edges().map { it.key } shouldBe listOf("m1->m2")
            moved.recallsSince(0L).map { it.memoryId } shouldBe listOf("m1")
            moved.embeddingModel() shouldBe "fake"
        } finally { moved.close() }
        Files.list(home).use { s -> s.filter { it.fileName.toString().startsWith("arcadedb.migrated-") }.toList() }.single()
            .let { aside -> Files.walk(aside).use { w -> w.anyMatch { Files.isRegularFile(it) } } } shouldBe true
    }

    test("two palaces in one process share the host — no lock-out on a runtime rebuild") {
        val home = tempdir().toPath(); val root = tempdir().toPath()
        val a = JanesPalace(config(home, root), null, FakeEmbeddingProvider())
        val b = JanesPalace(config(home, root), null, FakeEmbeddingProvider())
        try {
            runBlocking { a.rememberCommitment("pay rent", "s", System.currentTimeMillis()) }.shouldNotBeNull()
            b.browse(BrowseFilter()).map { it.text } shouldBe listOf("pay rent")
        } finally { a.close(); b.close() }
    }

    test("a host built for another memory schema refuses memory") {
        val root = tempdir().toPath()
        ArcadeHost.open(root, engineVersion = "future").use { other ->
            other.register(PalaceStore.DATABASE, PalaceStore.SCHEMA_VERSION + 1)
            shouldThrow<IncompatibleHostException> { JanesPalace(config(tempdir().toPath(), root), null, FakeEmbeddingProvider()) }
        }
    }
})
