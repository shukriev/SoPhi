package dev.sophi.context

import dev.sophi.store.arcade.ArcadeStore
import dev.sophi.store.arcade.EmbeddedArcadeStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.ServerSocket

class ChunkEmbedderTest : FunSpec({
    fun indexed(files: Map<String, String>): ArcadeStore =
        indexedStore(autoClose(EmbeddedArcadeStore.open(tempdir().toPath())), tempdir().toPath(), files)
    fun count(s: ArcadeStore, sql: String) = (s.query(sql).single()["n"] as Number).toInt()

    test("embeds docs first, shares one vector per identical chunk, then reports nothing pending") {
        val s = indexed(mapOf("README.md" to "# Cars\n\nAbout the car.\n", "a/A.kt" to "class Same", "b/B.kt" to "class Same"))
        val fake = FakeEmbeddings()
        val e = ChunkEmbedder(fake, "fake", batch = 1).also { it.prepare(s) }
        e.embedPending(s, max = 1) shouldBe 1
        fake.seen.single() shouldContain "README.md"          // docs first
        while (e.embedPending(s) > 0) Unit
        count(s, "SELECT count(*) AS n FROM ChunkVec") shouldBe 2   // README + one shared "class Same"
        count(s, "SELECT count(*) AS n FROM Chunk WHERE vec IS NULL") shouldBe 0
        fake.seen.size shouldBe 2
    }

    test("a cancelled embedPending stops between batches") {
        val s = indexed(mapOf("a/A.kt" to "class A", "b/B.kt" to "class B"))
        val fake = FakeEmbeddings()
        val e = ChunkEmbedder(fake, "fake", batch = 1).also { it.prepare(s) }
        var calls = 0
        e.embedPending(s, cancelled = { calls++ > 0 })
        fake.seen.size shouldBe 1
        count(s, "SELECT count(*) AS n FROM Chunk WHERE vec IS NULL") shouldBe 1
    }

    test("a model or dims change drops every vector and re-embeds") {
        val s = indexed(mapOf("a/A.kt" to "class Car"))
        ChunkEmbedder(FakeEmbeddings(4), "m1").also { it.prepare(s); while (it.embedPending(s) > 0) Unit }
        val e2 = ChunkEmbedder(FakeEmbeddings(6), "m2").also { it.prepare(s) }
        count(s, "SELECT count(*) AS n FROM ChunkVec") shouldBe 0
        while (e2.embedPending(s) > 0) Unit
        s.nearestVectors(ContextSchema.CHUNKVEC, "emb", FloatArray(6).also { it[0] = 1f }, 5).size shouldBe 1
        s.query("SELECT model, dims FROM ContextMeta WHERE id = 'embed'").single().let { it["model"] shouldBe "m2"; (it["dims"] as Number).toInt() shouldBe 6 }
    }

    test("non-loopback URLs are refused with one log line; loopback is accepted; unset or blank knob = off") {
        val logs = mutableListOf<String>()
        ChunkEmbedder.create("https://api.openai.com/v1", "m", 8) { logs += it }.shouldBeNull()
        ChunkEmbedder.create("http://10.0.0.5:1234/v1", "m", 8) { logs += it }.shouldBeNull()
        ChunkEmbedder.create("http://localhost.evil.com/v1", "m", 8) { logs += it }.shouldBeNull()
        logs.size shouldBe 3
        listOf("http://localhost:1234/v1", "http://127.0.0.1:1234/v1", "http://[::1]:1234/v1").forEach {
            ChunkEmbedder.create(it, "m", 8) { l -> logs += l }.shouldNotBeNull()
        }
        logs.size shouldBe 3
        ChunkEmbedder.fromKnobs(emptyMap()).shouldBeNull()
        ChunkEmbedder.fromKnobs(mapOf("SOPHI_CONTEXT_EMBED" to "")).shouldBeNull()
        ChunkEmbedder.fromKnobs(mapOf("SOPHI_CONTEXT_EMBED" to "  ")).shouldBeNull()
        ChunkEmbedder.fromKnobs(mapOf("SOPHI_CONTEXT_EMBED" to "nomic", "SOPHI_CONTEXT_EMBED_DIMS" to "512"))!!.provider.dimensions shouldBe 512
    }

    test("queryVector is null when the provider fails") {
        ChunkEmbedder(FakeEmbeddings(fail = true), "m").queryVector("car").shouldBeNull()
    }

    test("queryVector gives up after the query timeout when the embedding server hangs") {
        ServerSocket(0).use { server ->   // accepts the connection, never answers
            val hung = Thread { runCatching { while (true) server.accept() } }.apply { isDaemon = true; start() }
            val e = ChunkEmbedder.create("http://127.0.0.1:${server.localPort}/v1", "m", 8, queryTimeoutMillis = 300).shouldNotBeNull()
            val t0 = System.currentTimeMillis()
            e.queryVector("car").shouldBeNull()
            (System.currentTimeMillis() - t0) shouldBeLessThan 3_000L
            hung.interrupt()
        }
    }
})
