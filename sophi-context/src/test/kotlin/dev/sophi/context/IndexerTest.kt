package dev.sophi.context

import dev.sophi.store.arcade.ArcadeStore
import dev.sophi.store.arcade.EmbeddedArcadeStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class IndexerTest : FunSpec({
    fun setup(): Triple<ArcadeStore, Indexer, Path> {
        val store = autoClose(EmbeddedArcadeStore.open(tempdir().toPath()))
        ContextSchema.ensure(store)
        val root = tempdir().toPath()
        store.upsertDocument(ContextSchema.PROJECT, "p1", mapOf("root" to root.toString(), "name" to "demo", "status" to "active"))
        return Triple(store, Indexer(store, GitProbe.none), root)
    }
    fun Path.write(rel: String, text: String) = resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, text) }
    fun df(s: ArcadeStore, term: String) = (s.query("SELECT df FROM TermStat WHERE id = ?", term).firstOrNull()?.get("df") as Number?)?.toInt() ?: 0

    test("first scan indexes chunks, file records and document frequencies") {
        val (s, ix, root) = setup()
        root.write("src/Compactor.kt", "class ContextCompactor {\n fun compact() = 1\n}")
        root.write("README.md", "# Demo\n\nCompaction demo.\n")
        ix.scan("p1") shouldBe ScanStats(added = 2, changed = 0, removed = 0, unchanged = 0, partial = false)
        s.query("SELECT count(*) AS n FROM File WHERE projectId = ?", "p1").single()["n"].toString().toInt() shouldBe 2
        (df(s, "compactor") >= 1) shouldBe true
        s.fullText("Chunk", "toks", "compactor", listOf("path"), 10).map { it["path"] } shouldBe listOf("src/Compactor.kt")
    }

    test("rescan picks up edit, create and delete; df stays consistent") {
        val (s, ix, root) = setup()
        root.write("a.kt", "class Alpha"); root.write("b.kt", "class Beta")
        ix.scan("p1")
        val before = df(s, "alpha")
        Thread.sleep(5)
        root.write("a.kt", "class Gamma")          // edit: alpha gone, gamma in
        Files.delete(root.resolve("b.kt"))          // delete
        root.write("c.kt", "class Delta")           // create
        ix.scan("p1") shouldBe ScanStats(added = 1, changed = 1, removed = 1, unchanged = 0, partial = false)
        before shouldBe 1
        df(s, "alpha") shouldBe 0
        df(s, "gamma") shouldBe 1
        df(s, "beta") shouldBe 0
        s.fullText("Chunk", "toks", "beta", listOf("path"), 10) shouldBe emptyList()
        val meta = ContextSchema.meta(s)!!
        meta.chunkCount shouldBe s.query("SELECT count(*) AS n FROM Chunk").single()["n"].toString().toLong()
    }

    test("unchanged files are skipped by stat, and by hash when only mtime moved") {
        val (_, ix, root) = setup()
        val f = root.write("a.kt", "class Alpha")
        ix.scan("p1")
        ix.scan("p1").unchanged shouldBe 1
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000))
        ix.scan("p1").let { it.changed shouldBe 0; it.unchanged shouldBe 1 }
    }

    test("a version bump rebuilds: everything is reindexed on the next scan") {
        val (s, ix, root) = setup()
        root.write("a.kt", "class Alpha")
        ix.scan("p1")
        ix.rebuildIfStale(schemaVersion = 99)
        s.documents(ContextSchema.FILE) shouldBe emptyList()
        ix.scan("p1").added shouldBe 1
    }

    test("dropProject removes its files, chunks and df contributions") {
        val (s, ix, root) = setup()
        root.write("a.kt", "class Alpha")
        ix.scan("p1"); ix.dropProject("p1")
        s.documents(ContextSchema.CHUNK) shouldBe emptyList()
        df(s, "alpha") shouldBe 0
    }
})
