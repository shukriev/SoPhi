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
})
