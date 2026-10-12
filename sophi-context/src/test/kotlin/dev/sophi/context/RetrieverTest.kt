package dev.sophi.context

import dev.sophi.store.arcade.EmbeddedArcadeStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class RetrieverTest : FunSpec({
    fun Path.write(rel: String, text: String) = resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, text) }
    fun indexed(vararg projects: Pair<String, Map<String, String>>): Retriever {
        val store = autoClose(EmbeddedArcadeStore.open(tempdir().toPath()))
        ContextSchema.ensure(store)
        val ix = Indexer(store, GitProbe.none)
        ix.rebuildIfStale(1)
        projects.forEach { (id, files) ->
            val root = tempdir().toPath(); files.forEach { (p, t) -> root.write(p, t) }
            store.upsertDocument(ContextSchema.PROJECT, id, mapOf("root" to root.toString(), "name" to id, "status" to "active"))
            ix.scan(id)
        }
        return Retriever(store)
    }

    test("BM25 prefers the chunk where the rare query term is concentrated") {
        val r = indexed("p" to mapOf(
            "core/Compactor.kt" to "class ContextCompactor {\n fun compact() { summarise older rounds }\n}",
            "core/Other.kt" to "class Other {\n fun run() { session session session }\n}"))
        r.search("where are older rounds summarised", listOf("p")).first().path shouldBe "core/Compactor.kt"
    }

    test("source outranks its test for the same words; name match boosts") {
        val r = indexed("p" to mapOf(
            "src/main/HubPort.kt" to "class HubPort { val port = 8765 }",
            "src/test/HubPortTest.kt" to "class HubPortTest { fun port() { HubPort().port } }"))
        r.search("hub port", listOf("p")).map { it.path }.first() shouldBe "src/main/HubPort.kt"
    }

    test("only searches the projects in scope, one hit per file") {
        val r = indexed(
            "a" to mapOf("x.kt" to "class Alpha {}\nfun alpha() {}\nfun alphaAgain() {}"),
            "b" to mapOf("y.kt" to "class Alpha {}"))
        r.search("alpha", listOf("a")).map { it.projectId to it.path } shouldContainExactly listOf("a" to "x.kt")
    }

    test("caps results per directory") {
        val files = (1..6).associate { "pkg/F$it.kt" to "class Widget$it { fun widget() {} }" } + ("other/G.kt" to "class Widget { }")
        val r = indexed("p" to files)
        val hits = r.search("widget", listOf("p"), limit = 10)
        hits.count { it.path.startsWith("pkg/") } shouldBe 3
        hits.any { it.path == "other/G.kt" } shouldBe true
    }

    test("no match and empty query return nothing") {
        val r = indexed("p" to mapOf("a.kt" to "class Alpha"))
        r.search("zebra", listOf("p")) shouldBe emptyList()
        r.search("the and of", listOf("p")) shouldBe emptyList()
        r.search("alpha", emptyList()) shouldBe emptyList()
    }
})
