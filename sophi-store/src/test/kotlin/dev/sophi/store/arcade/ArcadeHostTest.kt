package dev.sophi.store.arcade

import com.arcadedb.serializer.json.JSONObject
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class ArcadeHostTest : FunSpec({

    fun open(root: java.nio.file.Path, schema: Int = 1, wait: Long = 3_000) =
        ArcadeHost.open(root, database = "ctx", schemaVersion = schema, engineVersion = "test", waitForHostMillis = wait)

    test("first opener hosts and publishes a 0600 host.json; second opener is a client sharing the data") {
        val root = tempdir().toPath()
        open(root).use { host ->
            host.role shouldBe Role.HOST
            val json = root.resolve("host.json")
            PosixFilePermissions.toString(Files.getPosixFilePermissions(json)) shouldBe "rw-------"
            JSONObject(Files.readString(json)).getLong("pid") shouldBe ProcessHandle.current().pid()

            host.withStore { it.ensureSchema(documentTypes = listOf("Note")); it.upsertDocument("Note", "h", mapOf("who" to "host")) }
            open(root).use { client ->
                client.role shouldBe Role.CLIENT
                client.withStore { it.upsertDocument("Note", "c", mapOf("who" to "client")) }
                client.withStore { s -> s.documents("Note").map { it["id"] } } shouldContainExactlyInAnyOrder listOf("h", "c")
            }
        }
    }

    test("client with a different schemaVersion is refused") {
        val root = tempdir().toPath()
        open(root, schema = 1).use {
            shouldThrow<IncompatibleHostException> { open(root, schema = 2) }
        }
    }

    test("when the host closes, the client's next call takes over and keeps the data") {
        val root = tempdir().toPath()
        val host = open(root)
        host.withStore { it.ensureSchema(documentTypes = listOf("Note")); it.upsertDocument("Note", "kept", mapOf("v" to 1)) }
        open(root).use { client ->
            client.role shouldBe Role.CLIENT
            host.close()
            val started = System.nanoTime()
            val ids = client.withStore { s -> s.documents("Note").map { it["id"] } }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            client.role shouldBe Role.HOST
            ids shouldBe listOf("kept")
            elapsedMs shouldBeLessThan 2_000L
        }
    }

    test("client ignores host.json whose pid is dead and gives up after the wait") {
        val root = tempdir().toPath()
        // Hold the lock from a second channel so this JVM can't become host, and leave a stale file.
        Files.createDirectories(root)
        java.nio.channels.FileChannel.open(root.resolve("host.lock"),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { ch ->
            ch.lock().use {
                Files.writeString(root.resolve("host.json"),
                    JSONObject().put("pid", Long.MAX_VALUE).put("port", 1).put("engineVersion", "x").put("schemaVersion", 1).toString())
                val started = System.nanoTime()
                shouldThrow<IllegalStateException> { open(root, wait = 500) }
                ((System.nanoTime() - started) / 1_000_000) shouldBeLessThan 2_000L
            }
        }
    }

    fun externalLock(root: java.nio.file.Path) = java.nio.channels.FileChannel.open(root.resolve("host.lock"),
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)

    test("a client that finds the lock held but no host.json takes over once the lock is released") {
        val root = tempdir().toPath()
        val ch = externalLock(root)
        val lock = ch.lock()
        var host: ArcadeHost? = null
        val t = Thread { host = open(root, wait = 3_000) }
        t.start()
        Thread.sleep(300)
        lock.release(); ch.close()
        t.join(5_000)
        try { host?.role shouldBe Role.HOST } finally { host?.close() }
    }

    test("host.json naming a live pid does not make us a client when the lock is free") {
        val root = tempdir().toPath()
        Files.writeString(root.resolve("host.json"),
            JSONObject().put("pid", ProcessHandle.current().pid()).put("port", 1).put("engineVersion", "x").put("schemaVersion", 1).toString())
        open(root).use { it.role shouldBe Role.HOST }
    }

    test("concurrent withStore calls after the host closes all succeed and the client ends as host") {
        val root = tempdir().toPath()
        val host = open(root)
        host.withStore { it.ensureSchema(documentTypes = listOf("Note")); it.upsertDocument("Note", "k", mapOf("v" to 1)) }
        open(root).use { client ->
            host.close()
            val results = java.util.concurrent.ConcurrentLinkedQueue<Any>()
            (1..4).map { Thread { results.add(runCatching { client.withStore { s -> s.documents("Note").size } }.getOrElse { e -> e }) } }
                .onEach { it.start() }.onEach { it.join(10_000) }
            results.toList() shouldBe listOf(1, 1, 1, 1)
            client.role shouldBe Role.HOST
        }
    }

    test("a failed takeover releases the lock so a later call can still become host") {
        val root = tempdir().toPath()
        val host = open(root)
        host.withStore { it.ensureSchema(documentTypes = listOf("Note")) }
        open(root, wait = 500).use { client ->
            host.close()
            // Make the database directory unusable so LocalArcadeServer/database creation fails mid-takeover.
            val dbs = root.resolve("databases")
            val moved = root.resolve("databases.moved")
            Files.move(dbs, moved)
            Files.writeString(dbs, "not a directory")
            shouldThrow<Exception> { client.withStore { s -> s.documents("Note") } }
            Files.delete(dbs)
            Files.move(moved, dbs)
            client.withStore { s -> s.documents("Note") }
            client.role shouldBe Role.HOST
        }
    }
})
