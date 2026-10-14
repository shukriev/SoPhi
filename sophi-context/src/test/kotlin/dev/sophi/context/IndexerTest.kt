package dev.sophi.context

import dev.sophi.store.arcade.ArcadeStore
import dev.sophi.store.arcade.EmbeddedArcadeStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

class IndexerTest : FunSpec({
    fun setup(status: String = "active", mk: (ArcadeStore) -> Indexer = { Indexer(it, GitProbe.none) }): Triple<ArcadeStore, Indexer, Path> {
        val store = autoClose(EmbeddedArcadeStore.open(tempdir().toPath()))
        ContextSchema.ensure(store)
        val root = tempdir().toPath()
        store.upsertDocument(ContextSchema.PROJECT, "p1", mapOf("root" to root.toString(), "name" to "demo", "status" to status))
        return Triple(store, mk(store), root)
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
        root.write("a.kt", "class Gamma").also { Files.setLastModifiedTime(it, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000)) }        // edit: alpha gone, gamma in
        Files.delete(root.resolve("b.kt"))          // delete
        root.write("c.kt", "class Delta")           // create
        ix.scan("p1") shouldBe ScanStats(added = 1, changed = 1, removed = 1, unchanged = 0, partial = false)
        before shouldBe 1
        df(s, "alpha") shouldBe 0
        df(s, "gamma") shouldBe 1
        df(s, "beta") shouldBe 0
        s.fullText("Chunk", "toks", "beta", listOf("path"), 10) shouldBe emptyList()
        val meta = ContextSchema.meta(s)!!
        meta.totalLen shouldBe (s.query("SELECT sum(len) AS n FROM Chunk").single()["n"] as Number).toLong()
        meta.chunkCount shouldBe s.query("SELECT count(*) AS n FROM Chunk").single()["n"].toString().toLong()
    }

    test("unchanged files are skipped by stat, and by hash when only mtime moved") {
        val (s, ix, root) = setup()
        val f = root.write("a.kt", "class Alpha")
        ix.scan("p1")
        ix.scan("p1").unchanged shouldBe 1
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000))
        val newM = Files.getLastModifiedTime(f).toMillis()
        ix.scan("p1").let { it.changed shouldBe 0; it.unchanged shouldBe 1 }
        (s.documents(ContextSchema.FILE).single()["mtime"] as Number).toLong() shouldBe newM
        ix.scan("p1").unchanged shouldBe 1
    }

    test("a version bump rebuilds: everything is reindexed on the next scan") {
        val (s, ix, root) = setup()
        root.write("a.kt", "class Alpha")
        ix.scan("p1")
        ix.rebuildIfStale(schemaVersion = 99)
        s.documents(ContextSchema.FILE) shouldBe emptyList()
        s.query("SELECT lastScanAt FROM ${ContextSchema.PROJECT} WHERE id = ?", "p1").single()["lastScanAt"] shouldBe null
        ix.scan("p1").added shouldBe 1
    }

    test("dropProject removes its files, chunks and df contributions") {
        val (s, ix, root) = setup()
        root.write("a.kt", "class Alpha")
        ix.scan("p1"); ix.dropProject("p1")
        s.documents(ContextSchema.CHUNK) shouldBe emptyList()
        df(s, "alpha") shouldBe 0
    }

    test("a file whose analysis throws is skipped; the rest of the scan completes") {
        val (s, ix, root) = setup { Indexer(it, GitProbe.none, analyze = { p, r, l, t -> if (p == "bad.kt") error("boom") else Analyzers.analyze(p, r, l, t) }) }
        root.write("a.kt", "class Alpha"); root.write("b.kt", "class Beta")
        ix.scan("p1")
        Files.delete(root.resolve("b.kt")); root.write("bad.kt", "class Bad"); root.write("c.kt", "class Delta")
        ix.scan("p1") shouldBe ScanStats(added = 1, changed = 0, removed = 1, unchanged = 1, partial = false)
        s.documents(ContextSchema.FILE).map { it["path"] }.toSet() shouldBe setOf("a.kt", "c.kt")
        (s.query("SELECT lastScanAt FROM Project WHERE id = ?", "p1").single()["lastScanAt"] != null) shouldBe true
    }

    test("a paused project stays paused after a scan") {
        val (s, ix, root) = setup("paused")
        root.write("a.kt", "class Alpha")
        ix.scan("p1")
        s.query("SELECT status FROM Project WHERE id = ?", "p1").single()["status"] shouldBe "paused"
    }

    test("a partial listing deletes nothing") {
        val (s, _, root) = setup()
        root.write("a.kt", "class A"); root.write("b.kt", "class B"); root.write("c.kt", "class C")
        Indexer(s, GitProbe.none).scan("p1")
        Indexer(s, GitProbe.none, maxFiles = 2).scan("p1").partial shouldBe true
        s.documents(ContextSchema.FILE).size shouldBe 3
    }

    test("a vanished root marks the project missing and leaves the index alone") {
        val (s, ix, root) = setup()
        root.write("a.kt", "class A")
        ix.scan("p1")
        root.toFile().deleteRecursively()
        ix.scan("p1") shouldBe ScanStats(0, 0, 0, 0, false)
        s.query("SELECT status FROM Project WHERE id = ?", "p1").single()["status"] shouldBe "missing"
        s.documents(ContextSchema.FILE).size shouldBe 1
        s.documents(ContextSchema.CHUNK).isNotEmpty() shouldBe true
    }

    test("an interrupt during a file analysis propagates and keeps the interrupt flag") {
        val (_, ix, root) = setup(mk = { Indexer(it, GitProbe.none, analyze = { _, _, _, _ -> throw InterruptedException("stop") }) })
        root.write("a.kt", "class Alpha")
        try {
            runCatching { ix.scan("p1") }.exceptionOrNull().shouldBeInstanceOf<InterruptedException>()
            Thread.currentThread().isInterrupted shouldBe true
        } finally { Thread.interrupted() }
    }

    test("a cancelled scan stops without deletions or a lastScanAt update") {
        var calls = 0
        val (s, ix, root) = setup(mk = { Indexer(it, GitProbe.none, cancelled = { calls++ >= 1 }) })
        root.write("a.kt", "class Alpha"); root.write("b.kt", "class Beta")
        ix.scan("p1")
        s.documents(ContextSchema.FILE).size shouldBe 1
        s.query("SELECT lastScanAt FROM Project WHERE id = ?", "p1").single()["lastScanAt"] shouldBe null
    }

    test("progress fields are set during a scan and cleared after it") {
        var checks = 0
        var mid: Map<String, Any?> = emptyMap()
        val (s, _, root) = setup()
        (1..5).forEach { root.write("f$it.kt", "class C$it") }
        // a cancelled scan clears progress on return, so observe the mid-scan state from inside the cancel hook
        val cancelled = Indexer(s, GitProbe.none, progressEvery = 2, cancelled = {
            (++checks > 3).also { if (it) mid = s.query("SELECT scanned, scanTotal FROM ${ContextSchema.PROJECT} WHERE id = ?", "p1").single() }
        })
        cancelled.scan("p1")
        s.query("SELECT scanned FROM ${ContextSchema.PROJECT} WHERE id = ?", "p1").single()["scanned"].shouldBeNull()
        (mid["scanTotal"] as Number).toInt() shouldBe 5
        (mid["scanned"] as Number).toInt() shouldBe 2
        Indexer(s, GitProbe.none, progressEvery = 2).scan("p1")
        val done = s.query("SELECT scanned, scanTotal FROM ${ContextSchema.PROJECT} WHERE id = ?", "p1").single()
        done["scanned"].shouldBeNull(); done["scanTotal"].shouldBeNull()
    }

    test("a scan of a missing root leaves no progress fields") {
        val (s, ix, root) = setup()
        s.command("UPDATE ${ContextSchema.PROJECT} SET scanned = 1, scanTotal = 5 WHERE id = ?", "p1")
        Files.delete(root)
        ix.scan("p1")
        val r = s.query("SELECT scanned, scanTotal FROM ${ContextSchema.PROJECT} WHERE id = ?", "p1").single()
        r["scanned"].shouldBeNull(); r["scanTotal"].shouldBeNull()
    }
})
