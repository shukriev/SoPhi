package dev.sophi.store.arcade

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class SharedArcadeHostTest : FunSpec({
    test("leases on one root share one host, which stops when the last lease closes") {
        val root = tempdir().toPath()
        val a = SharedArcadeHost.acquire(root)
        val b = SharedArcadeHost.acquire(root.resolve("x").resolve(".."))   // same root, spelled differently
        (a.host === b.host) shouldBe true
        a.close(); a.close()                                               // idempotent: b still holds it
        b.host.register("t", 1)
        b.host.withStore("t") { it.ensureSchema(documentTypes = listOf("Note")); it.documents("Note") } shouldBe emptyList()
        Files.exists(root.resolve("host.json")) shouldBe true
        b.close()
        Files.exists(root.resolve("host.json")) shouldBe false
    }

    test("a failover store retries a call on the new host after the old one exits, data intact") {
        val root = tempdir().toPath()
        val other = ArcadeHost.open(root, engineVersion = "other")        // stands in for another process
        other.role shouldBe Role.HOST
        FailoverArcadeStore(SharedArcadeHost.acquire(root), "mem", 1).use { s ->
            s.role shouldBe Role.CLIENT
            s.ensureSchema(documentTypes = listOf("Note"))
            s.upsertDocument("Note", "a", mapOf("v" to 1))
            other.close()
            s.documents("Note").map { it["id"] } shouldBe listOf("a")
            s.role shouldBe Role.HOST
        }
    }

    test("a transaction that fails over before its first write is retried as one block, leaving one copy of each record") {
        val root = tempdir().toPath()
        val other = ArcadeHost.open(root, engineVersion = "other")
        FailoverArcadeStore(SharedArcadeHost.acquire(root), "mem", 1).use { s ->
            s.ensureSchema(vertexTypes = listOf("Vec"))
            s.insertVertex("Vec", "m1", mapOf("n" to 1))
            other.close()
            // The first call after the host died fails on the old client and is retried whole on the new host.
            s.transaction { s.deleteVertex("Vec", "m1"); s.insertVertex("Vec", "m1", mapOf("n" to 2)) }
            s.transaction { s.deleteVertex("Vec", "m1"); s.insertVertex("Vec", "m1", mapOf("n" to 3)) }
            s.queryVertices("Vec").map { (it["n"] as Number).toInt() } shouldBe listOf(3)
        }
    }

    test("closing a failover store unregisters its database") {
        val root = tempdir().toPath()
        val keep = SharedArcadeHost.acquire(root)
        FailoverArcadeStore(SharedArcadeHost.acquire(root), "mem", 1).use { keep.host.users("mem") shouldBe 1 }
        keep.host.users("mem") shouldBe 0
        keep.close()
    }
})
