package dev.sophi.context

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class ContextEvalTest : FunSpec({
    fun Path.write(rel: String, text: String) = resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

    test("scores hits, MRR and the grep baseline on a tiny corpus") {
        val root = tempdir().toPath()
        root.write("core/Compactor.kt", "class ContextCompactor { fun compact() { summarise older rounds when the window fills } }")
        root.write("hub/HubServer.kt", "class HubServer(val port: Int = 8765)")
        root.write("docs/notes.md", "# Notes\n\nNothing relevant.\n")
        val queries = listOf(
            EvalQuery("summarise older rounds", listOf("demo/core/Compactor.kt")),
            EvalQuery("hub server port", listOf("demo/hub/HubServer.kt")),
            EvalQuery("zebra crossing", listOf("demo/nope.kt"))
        )
        val r = ContextEval(mapOf("demo" to root), GitProbe.none).run(queries)
        r.n shouldBe 3
        r.hitAt1 shouldBe (2.0 / 3)
        r.mrr shouldBeGreaterThan 0.6
        r.misses.size shouldBe 1
    }

    test("hintGrid: open gate covers the relevant queries, a huge minScore covers none") {
        val root = tempdir().toPath()
        root.write("core/Compactor.kt", "class ContextCompactor { fun compact() { summarise older rounds when the window fills } }")
        root.write("hub/HubServer.kt", "class HubServer(val port: Int = 8765) { hub server port listener }")
        root.write("docs/notes.md", "# Notes\n\nNothing relevant.\n")
        val queries = listOf(
            EvalQuery("summarise older rounds when window fills", listOf("demo/core/Compactor.kt")),
            EvalQuery("hub server port listener", listOf("demo/hub/HubServer.kt")),
            EvalQuery("zebra crossing giraffe", listOf("demo/nope.kt"))
        )
        val g = ContextEval(mapOf("demo" to root), GitProbe.none).hintGrid(queries, listOf(0.0, 1e6), listOf(1.0))
        g[0].coverage shouldBe (2.0 / 3)
        g[0].precision shouldBe 1.0
        g[1].coverage shouldBe 0.0
        g[1].precision shouldBe 1.0
    }

    test("load reads TSV and skips comments") {
        val f = tempdir().toPath().resolve("q.tsv")
        Files.writeString(f, "# header\nwhere is x\ta/b.kt,c.kt\n\n")
        ContextEval.load(f) shouldBe listOf(EvalQuery("where is x", listOf("a/b.kt", "c.kt")))
    }

    test("embedding variant: a query with no shared words finds its file") {
        val root = tempdir().toPath()
        root.write("cars/Garage.kt", "class Garage { fun park(car: Car) {} }")
        root.write("fruit/Bowl.kt", "class Bowl { val banana = 1 }")
        val q = listOf(EvalQuery("automobile", listOf("demo/cars/Garage.kt")))
        ContextEval(mapOf("demo" to root), GitProbe.none).run(q).hitAt1 shouldBe 0.0
        ContextEval(mapOf("demo" to root), GitProbe.none, embedder = ChunkEmbedder(FakeEmbeddings(), "fake")).run(q).hitAt1 shouldBe 1.0
    }

    test("llm variant summarises only files in some query's lexical top 10, and reuses the cache by content hash") {
        val root = tempdir().toPath()
        root.write("hub/Hub.kt", "class Hub { fun start() {} }")
        root.write("other/Other.kt", "class Other")
        val llm = FakeLlm("Accepts remote control sessions.")
        val cache = tempdir().toPath().resolve("cache.tsv")
        val q = listOf(EvalQuery("hub start", listOf("demo/hub/Hub.kt")), EvalQuery("remote control sessions", listOf("demo/hub/Hub.kt")))
        ContextEval(mapOf("demo" to root), GitProbe.none, summaries = LlmSummaries(FileSummariser(llm, "m"), cache)).run(q).hitAt1 shouldBe 1.0
        llm.requests.size shouldBe 1          // Other.kt was never retrieved, so never summarised
        ContextEval(mapOf("demo" to root), GitProbe.none, summaries = LlmSummaries(FileSummariser(llm, "m"), cache)).run(q)
        llm.requests.size shouldBe 1          // cache hit
    }

    test("the eval's IndexOptions reach the Indexer: summary words in the preamble chunk lift the file's rank") {
        val root = tempdir().toPath()
        root.write("a/Hub.kt", "package a\n\nimport b.C\n\n/** Listens for remote sessions. */\nclass Hub {\n" + (1..30).joinToString("\n") { " val field$it = $it" } + "\n}\n")
        root.write("b/Decoy.kt", "class Decoy { val listens = 1; val remote = 2; val sessions = 3; val a = 4; val b = 5 }")
        val q = listOf(EvalQuery("listens remote sessions", listOf("demo/a/Hub.kt")))
        val off = ContextEval(mapOf("demo" to root), GitProbe.none).run(q)
        val on = ContextEval(mapOf("demo" to root), GitProbe.none, options = IndexOptions(summaryTokens = true)).run(q)
        on.hitAt1 shouldBe 1.0
        off.hitAt1 shouldBe 0.0
    }
})
