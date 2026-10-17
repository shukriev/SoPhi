package dev.sophi.store.arcade

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

class LegacyMigrationTest : FunSpec({
    val layout = LegacyLayout(vertexTypes = listOf("Thing"), edgeTypes = mapOf("Link" to "Thing"), documentTypes = listOf("Note"))

    /** The pre-M6 layout: embedded at home/arcadedb, a vector set with putVector on the vertex itself. */
    fun seedLegacy(home: Path): Path = home.also {
        EmbeddedArcadeStore.open(home).use { db ->
            db.ensureSchema(vertexTypes = listOf("Thing"), edgeTypes = listOf("Link"), documentTypes = listOf("Note"))
            db.upsertVertex("Thing", "a", mapOf("name" to "A")); db.upsertVertex("Thing", "b", mapOf("name" to "B"))
            db.putVector("Thing", "a", "vec", floatArrayOf(1f, 0f))
            db.upsertEdge("Link", "Thing", "a", "b", mapOf("label" to "x"))
            db.upsertDocument("Note", "n1", mapOf("text" to "hello"))
        }
    }
    fun legacyHome(): Path = seedLegacy(tempdir().toPath())
    fun target(): ArcadeStore = autoClose(EmbeddedArcadeStore.open(tempdir().toPath()))
    fun moved(home: Path) = Files.list(home).use { s -> s.anyMatch { it.fileName.toString().startsWith("arcadedb.migrated-") } }

    test("copies vertices, edges, documents and vectors, writes a marker, moves the old directory aside") {
        val home = legacyHome(); val t = target()
        LegacyMigration.run(t, home, layout) shouldBe LegacyMigration.Result.Migrated(mapOf("Thing" to 2, "Note" to 1, "Link" to 1))
        t.getVertex("Thing", "a")!!["name"] shouldBe "A"
        vectorOf(t.getVertex("Thing", "a")!!["vec"])!!.toList() shouldBe listOf(1f, 0f)
        t.edges("Link").single()["label"] shouldBe "x"
        t.documents("Note").single()["text"] shouldBe "hello"
        t.documents(LegacyMigration.MARKER).single()["counts"] shouldBe "Thing=2,Note=1,Link=1"
        Files.exists(home.resolve("arcadedb")) shouldBe false
        moved(home) shouldBe true
    }

    test("a second run is a no-op once marked") {
        val home = legacyHome(); val t = target()
        LegacyMigration.run(t, home, layout)
        LegacyMigration.run(t, home, layout) shouldBe LegacyMigration.Result.AlreadyMigrated
        t.queryVertices("Thing").size shouldBe 2
    }

    test("a crash mid-copy (MigrationInProgress present) is cleared and redone; the SophiSchema marker survives") {
        val home = legacyHome()
        val t = autoClose(FailoverArcadeStore(SharedArcadeHost.acquire(tempdir().toPath()), "mem", 7))
        t.ensureSchema(vertexTypes = listOf("Thing"), documentTypes = listOf("Note", LegacyMigration.IN_PROGRESS))
        t.upsertDocument(LegacyMigration.IN_PROGRESS, "legacy", mapOf("at" to 1L))   // what a killed copy leaves behind
        t.upsertVertex("Thing", "stale", mapOf("name" to "half-copied"))
        t.documents("SophiSchema").single()["version"] shouldBe 7
        // Even a layout that names the schema marker must not clear, count or copy it.
        val greedy = layout.copy(documentTypes = layout.documentTypes + "SophiSchema")
        LegacyMigration.run(t, home, greedy) shouldBe LegacyMigration.Result.Migrated(mapOf("Thing" to 2, "Note" to 1, "Link" to 1))
        t.queryVertices("Thing").map { it["id"] }.toSet() shouldBe setOf("a", "b")
        t.documents("SophiSchema").single()["version"] shouldBe 7
        t.documents(LegacyMigration.IN_PROGRESS) shouldBe emptyList()
        LegacyMigration.isMarked(t) shouldBe true
    }

    test("a non-empty unmarked target is never cleared: TargetNotEmpty, legacy dir untouched") {
        val home = legacyHome(); val t = target()
        t.ensureSchema(vertexTypes = listOf("Thing"))
        t.upsertVertex("Thing", "live", mapOf("name" to "kept"))
        LegacyMigration.run(t, home, layout) shouldBe LegacyMigration.Result.TargetNotEmpty
        t.queryVertices("Thing").map { it["id"] } shouldBe listOf("live")
        LegacyMigration.isMarked(t) shouldBe false
        Files.isDirectory(home.resolve("arcadedb")) shouldBe true
        moved(home) shouldBe false
    }

    test("a rename-aside that collides with an existing aside dir is logged, not thrown") {
        val home = legacyHome(); val t = target()
        LegacyMigration.run(t, home, layout, now = { 42 }).shouldBeInstanceOf<LegacyMigration.Result.Migrated>()
        Files.isDirectory(home.resolve("arcadedb.migrated-42")) shouldBe true
        seedLegacy(home)   // a stray dir; moving it to arcadedb.migrated-42 again must fail softly
        LegacyMigration.run(t, home, layout, now = { 42 }) shouldBe LegacyMigration.Result.AlreadyMigrated
        Files.isDirectory(home.resolve("arcadedb")) shouldBe true
        t.queryVertices("Thing").size shouldBe 2
    }

    test("a locked stray dir is left in place this run") {
        val home = tempdir().toPath(); val t = target()
        LegacyMigration.run(t, home, layout) shouldBe LegacyMigration.Result.NothingToMigrate
        seedLegacy(home)
        EmbeddedArcadeStore.open(home).use {   // an older Sophi is still using it
            LegacyMigration.run(t, home, layout) shouldBe LegacyMigration.Result.AlreadyMigrated
            Files.isDirectory(home.resolve("arcadedb")) shouldBe true
            moved(home) shouldBe false
        }
    }

    test("the marker records edges skipped for a missing endpoint") {
        val home = tempdir().toPath()
        EmbeddedArcadeStore.open(home).use { db ->
            db.ensureSchema(vertexTypes = listOf("Thing"), edgeTypes = listOf("Link"))
            listOf("a", "b", "c").forEach { db.upsertVertex("Thing", it, emptyMap()) }
            db.upsertEdge("Link", "Thing", "a", "b", emptyMap())
            db.upsertEdge("Link", "Thing", "a", "c", emptyMap())
            db.command("UPDATE Link SET toId = 'ghost' WHERE toId = 'c'")   // an endpoint id that no vertex has
        }
        val t = target()
        LegacyMigration.run(t, home, layout) shouldBe
            LegacyMigration.Result.Migrated(mapOf("Thing" to 3, "Link" to 1), skipped = mapOf("Link" to 1))
        t.edges("Link").single()["toId"] shouldBe "b"
        t.documents(LegacyMigration.MARKER).single()["skipped"] shouldBe "Link=1"
    }

    test("a locked source leaves everything in place") {
        val home = legacyHome(); val t = target()
        EmbeddedArcadeStore.open(home).use {   // stands in for an older Sophi holding it
            LegacyMigration.run(t, home, layout).shouldBeInstanceOf<LegacyMigration.Result.SourceUnavailable>()
        }
        LegacyMigration.isMarked(t) shouldBe false
        Files.isDirectory(home.resolve("arcadedb")) shouldBe true
        moved(home) shouldBe false
    }

    test("no old directory: nothing to migrate") {
        LegacyMigration.run(target(), tempdir().toPath(), layout) shouldBe LegacyMigration.Result.NothingToMigrate
    }

    test("no legacy dir → marked; a later stray dir is moved aside, data kept") {
        val home = tempdir().toPath(); val t = target()
        LegacyMigration.run(t, home, layout) shouldBe LegacyMigration.Result.NothingToMigrate
        LegacyMigration.isMarked(t) shouldBe true
        t.ensureSchema(vertexTypes = listOf("Thing"))
        t.upsertVertex("Thing", "live", mapOf("name" to "kept"))
        seedLegacy(home)   // an older build recreated home/arcadedb
        LegacyMigration.run(t, home, layout) shouldBe LegacyMigration.Result.AlreadyMigrated
        t.queryVertices("Thing").map { it["id"] } shouldBe listOf("live")
        Files.exists(home.resolve("arcadedb")) shouldBe false
        moved(home) shouldBe true
    }

    test("SharedStore: the host migrates on open") {
        val home = legacyHome(); val root = tempdir().toPath()
        SharedStore.open(root, "mem", 1, legacyHome = home, layout = layout).use { s ->
            s.role shouldBe Role.HOST
            s.queryVertices("Thing").size shouldBe 2
        }
        moved(home) shouldBe true
    }

    test("SharedStore: a client with no legacy dir marks the db; a later stray dir reaching the host keeps the client's rows") {
        val home = tempdir().toPath(); val root = tempdir().toPath()
        ArcadeHost.open(root, engineVersion = "other").use {
            SharedStore.open(root, "mem", 1, legacyHome = home, layout = layout).use { s ->
                s.role shouldBe Role.CLIENT
                LegacyMigration.isMarked(s) shouldBe true
                s.ensureSchema(vertexTypes = listOf("Thing"))
                s.upsertVertex("Thing", "live", mapOf("name" to "client-written"))
            }
        }
        seedLegacy(home)
        SharedStore.open(root, "mem", 1, legacyHome = home, layout = layout).use { s ->
            s.role shouldBe Role.HOST
            s.queryVertices("Thing").map { it["id"] } shouldBe listOf("live")
        }
        moved(home) shouldBe true
    }

    test("SharedStore: a client refuses to start over unmigrated data") {
        val home = legacyHome(); val root = tempdir().toPath()
        ArcadeHost.open(root, engineVersion = "other").use {        // another process hosts and never opens "mem"
            shouldThrow<IllegalStateException> { SharedStore.open(root, "mem", 1, legacyHome = home, layout = layout) }
                .message shouldContain "hasn't moved to the shared store yet"
        }
        Files.isDirectory(home.resolve("arcadedb")) shouldBe true
    }
})
