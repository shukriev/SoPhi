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
        ArcadeHost.open(root, engineVersion = "test", waitForHostMillis = wait).also {
            try { it.register("ctx", schema) } catch (e: Exception) { it.close(); throw e }
        }

    test("first opener hosts and publishes a 0600 host.json; second opener is a client sharing the data") {
        val root = tempdir().toPath()
        open(root).use { host ->
            host.role shouldBe Role.HOST
            val json = root.resolve("host.json")
            PosixFilePermissions.toString(Files.getPosixFilePermissions(json)) shouldBe "rw-------"
            JSONObject(Files.readString(json)).getLong("pid") shouldBe ProcessHandle.current().pid()

            host.withStore("ctx") { it.ensureSchema(documentTypes = listOf("Note")); it.upsertDocument("Note", "h", mapOf("who" to "host")) }
            open(root).use { client ->
                client.role shouldBe Role.CLIENT
                client.withStore("ctx") { it.upsertDocument("Note", "c", mapOf("who" to "client")) }
                client.withStore("ctx") { s -> s.documents("Note").map { it["id"] } } shouldContainExactlyInAnyOrder listOf("h", "c")
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
        host.withStore("ctx") { it.ensureSchema(documentTypes = listOf("Note")); it.upsertDocument("Note", "kept", mapOf("v" to 1)) }
        open(root).use { client ->
            client.role shouldBe Role.CLIENT
            host.close()
            val started = System.nanoTime()
            val ids = client.withStore("ctx") { s -> s.documents("Note").map { it["id"] } }
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
        host.withStore("ctx") { it.ensureSchema(documentTypes = listOf("Note")); it.upsertDocument("Note", "k", mapOf("v" to 1)) }
        open(root).use { client ->
            host.close()
            val results = java.util.concurrent.ConcurrentLinkedQueue<Any>()
            (1..4).map { Thread { results.add(runCatching { client.withStore("ctx") { s -> s.documents("Note").size } }.getOrElse { e -> e }) } }
                .onEach { it.start() }.onEach { it.join(10_000) }
            results.toList() shouldBe listOf(1, 1, 1, 1)
            client.role shouldBe Role.HOST
        }
    }

    test("a failed takeover releases the lock so a later call can still become host") {
        val root = tempdir().toPath()
        val host = open(root)
        host.withStore("ctx") { it.ensureSchema(documentTypes = listOf("Note")) }
        open(root, wait = 500).use { client ->
            host.close()
            // Make the database directory unusable so LocalArcadeServer/database creation fails mid-takeover.
            val dbs = root.resolve("databases")
            val moved = root.resolve("databases.moved")
            Files.move(dbs, moved)
            Files.writeString(dbs, "not a directory")
            shouldThrow<Exception> { client.withStore("ctx") { s -> s.documents("Note") } }
            Files.delete(dbs)
            Files.move(moved, dbs)
            client.withStore("ctx") { s -> s.documents("Note") }
            client.role shouldBe Role.HOST
        }
    }

    test("stale host.json naming a live pid and the old port does not stop a client re-electing") {
        val root = tempdir().toPath()
        val host = open(root)
        host.withStore("ctx") { it.ensureSchema(documentTypes = listOf("Note")); it.upsertDocument("Note", "k", mapOf("v" to 1)) }
        open(root).use { client ->
            val stale = Files.readString(root.resolve("host.json"))
            host.close()
            Files.writeString(root.resolve("host.json"), stale) // same pid (this JVM), same port, but no lock holder
            client.withStore("ctx") { s -> s.documents("Note").size } shouldBe 1
            client.role shouldBe Role.HOST
        }
    }

    test("one host serves several databases; a second instance reaches each by name") {
        val root = tempdir().toPath()
        ArcadeHost.open(root, engineVersion = "test").use { host ->
            host.register("ctx", 1); host.register("mem", 1)
            host.withStore("ctx") { it.ensureSchema(documentTypes = listOf("Note")); it.upsertDocument("Note", "c", mapOf("v" to 1)) }
            host.withStore("mem") { it.ensureSchema(documentTypes = listOf("Note")); it.upsertDocument("Note", "m", mapOf("v" to 2)) }
            ArcadeHost.open(root, engineVersion = "test").use { client ->
                client.role shouldBe Role.CLIENT
                client.register("ctx", 1); client.register("mem", 1)
                client.withStore("ctx") { s -> s.documents("Note").map { it["id"] } } shouldBe listOf("c")
                client.withStore("mem") { s -> s.documents("Note").map { it["id"] } } shouldBe listOf("m")
            }
        }
    }

    test("host.json lists each schema and a sentinel schemaVersion that every pre-M6 client refuses") {
        val root = tempdir().toPath()
        ArcadeHost.open(root, engineVersion = "test").use { host ->
            host.register("ctx", 1); host.register("mem", 1) // all registered schemas agree; the sentinel stays
            val file = root.resolve("host.json")
            val json = JSONObject(Files.readString(file))
            json.getJSONObject("schemas").getInt("ctx") shouldBe 1
            json.getJSONObject("schemas").getInt("mem") shouldBe 1
            json.getInt("schemaVersion") shouldBe -1
            // A pre-M6 client reads only schemaVersion: drop `schemas` and let the old-format check run.
            json.remove("schemas")
            Files.writeString(file, json.toString())
            ArcadeHost.open(root, engineVersion = "m5").use { old ->
                old.role shouldBe Role.CLIENT
                shouldThrow<IncompatibleHostException> { old.register("ctx", 1) }
            }
        }
    }

    test("a client register checks a database the host registered after the client attached") {
        val root = tempdir().toPath()
        ArcadeHost.open(root, engineVersion = "test").use { host ->
            host.register("ctx", 1)
            ArcadeHost.open(root, engineVersion = "newer").use { client ->
                client.role shouldBe Role.CLIENT
                host.register("mem", 1)
                shouldThrow<IncompatibleHostException> { client.register("mem", 2) }
                client.register("ctx", 1)
            }
        }
    }

    test("a schema mismatch refuses only that database") {
        val root = tempdir().toPath()
        ArcadeHost.open(root, engineVersion = "test").use { host ->
            host.register("ctx", 1); host.register("mem", 1)
            host.withStore("ctx") { it.ensureSchema(documentTypes = listOf("Note")); it.upsertDocument("Note", "c", emptyMap()) }
            ArcadeHost.open(root, engineVersion = "newer").use { client ->
                shouldThrow<IncompatibleHostException> { client.register("mem", 2) }
                client.register("ctx", 1)
                client.withStore("ctx") { s -> s.documents("Note").size } shouldBe 1
            }
        }
    }

    test("an old-format host.json (one schemaVersion, no schemas) is checked against every database") {
        val root = tempdir().toPath()
        LocalArcadeServer.start(root).use { srv ->
            externalLock(root).use { ch -> ch.lock().use {
                Files.writeString(root.resolve("host.json"), JSONObject().put("pid", ProcessHandle.current().pid())
                    .put("port", srv.port).put("engineVersion", "m5").put("schemaVersion", 1).toString())
                ArcadeHost.open(root, engineVersion = "test").use { client ->
                    client.role shouldBe Role.CLIENT
                    client.register("context", 1)
                    shouldThrow<IncompatibleHostException> { client.register("memory", 2) }
                }
            } }
        }
    }

    test("users are counted; a detached database starts empty after its directory moves") {
        val root = tempdir().toPath()
        ArcadeHost.open(root, engineVersion = "test").use { host ->
            host.register("ctx", 1); host.register("ctx", 1)
            host.users("ctx") shouldBe 2
            host.unregister("ctx"); host.unregister("ctx")
            host.users("ctx") shouldBe 0
            host.withStore("ctx") { it.ensureSchema(documentTypes = listOf("Note")); it.upsertDocument("Note", "x", emptyMap()) }
            host.detach("ctx")
            Files.move(root.resolve("databases").resolve("ctx"), root.resolve("ctx.aside"))
            host.withStore("ctx") { s -> s.ensureSchema(documentTypes = listOf("Note")); s.documents("Note") } shouldBe emptyList()
        }
    }

    test("a corrupt database does not stop the host serving another") {
        val root = tempdir().toPath()
        ArcadeHost.open(root, engineVersion = "test").use { h -> h.register("bad", 1); h.withStore("bad") { it.ensureSchema(documentTypes = listOf("Note")) } }
        Files.walk(root.resolve("databases").resolve("bad")).use { s ->
            s.filter { Files.isRegularFile(it) }.forEach { Files.write(it, ByteArray(4096) { i -> (i * 31 + 7).toByte() }) }
        }
        ArcadeHost.open(root, engineVersion = "test").use { h ->
            h.register("good", 1)
            h.withStore("good") { it.ensureSchema(documentTypes = listOf("Note")); it.upsertDocument("Note", "ok", emptyMap()); it.documents("Note").size } shouldBe 1
        }
    }

    test("a client reaches a database the current host has not opened, on disk or new") {
        val root = tempdir().toPath()
        ArcadeHost.open(root, engineVersion = "test").use { h ->
            h.register("old", 1); h.withStore("old") { it.ensureSchema(documentTypes = listOf("Note")); it.upsertDocument("Note", "kept", emptyMap()) }
        }
        ArcadeHost.open(root, engineVersion = "test").use { host ->
            host.register("other", 1)
            ArcadeHost.open(root, engineVersion = "test").use { client ->
                client.register("old", 1)
                client.withStore("old") { s -> s.documents("Note").map { it["id"] } } shouldBe listOf("kept")
                client.register("fresh", 1)
                client.withStore("fresh") { s -> s.ensureSchema(documentTypes = listOf("Note")); s.documents("Note") } shouldBe emptyList()
            }
        }
    }

    test("a client registers a database first at a higher version; the host's lower register is refused on use") {
        val root = tempdir().toPath()
        ArcadeHost.open(root, engineVersion = "old").use { host ->
            ArcadeHost.open(root, engineVersion = "newer").use { client ->
                client.register("db", 2)
                client.withStore("db") { it.ensureSchema(documentTypes = listOf("Note")) }
            }
            host.register("db", 1)
            shouldThrow<IncompatibleHostException> { host.withStore("db") { it.documents("Note") } }
        }
    }

    test("two clients at different versions of a database the host never registers: the second is refused") {
        val root = tempdir().toPath()
        ArcadeHost.open(root, engineVersion = "test").use { host ->
            host.register("other", 1)
            ArcadeHost.open(root, engineVersion = "a").use { a ->
                ArcadeHost.open(root, engineVersion = "b").use { b ->
                    a.register("db", 1); a.withStore("db") { it.ensureSchema(documentTypes = listOf("Note")) }
                    b.register("db", 2)
                    shouldThrow<IncompatibleHostException> { b.withStore("db") { it.documents("Note") } }
                    a.withStore("db") { it.documents("Note") } shouldBe emptyList()
                }
            }
        }
    }

    test("the host may upgrade a database's schema 1 -> 2") {
        val root = tempdir().toPath()
        ArcadeHost.open(root, engineVersion = "v1").use { h ->
            h.register("db", 1); h.withStore("db") { it.ensureSchema(documentTypes = listOf("Note")); it.upsertDocument("Note", "k", emptyMap()) }
        }
        ArcadeHost.open(root, engineVersion = "v2").use { h ->
            h.register("db", 2)
            h.withStore("db") { it.documents("Note").size } shouldBe 1
        }
        ArcadeHost.open(root, engineVersion = "v1-again").use { h ->
            h.register("db", 1)
            shouldThrow<IncompatibleHostException> { h.withStore("db") { it.documents("Note") } }
        }
    }

    test("after the host detaches a database and moves its directory, the client recovers on its next call") {
        val root = tempdir().toPath()
        ArcadeHost.open(root, engineVersion = "test").use { host ->
            host.register("ctx", 1)
            host.withStore("ctx") { it.ensureSchema(documentTypes = listOf("Note")); it.upsertDocument("Note", "x", emptyMap()) }
            ArcadeHost.open(root, engineVersion = "test").use { client ->
                client.register("ctx", 1)
                client.withStore("ctx") { s -> s.documents("Note").size } shouldBe 1
                host.detach("ctx")
                Files.move(root.resolve("databases").resolve("ctx"), root.resolve("ctx.aside"))
                runCatching { client.withStore("ctx") { s -> s.documents("Note") } } // the stale connection may fail once
                client.withStore("ctx") { s -> s.ensureSchema(documentTypes = listOf("Note")); s.documents("Note") } shouldBe emptyList()
            }
        }
    }

    test("register rejects database names outside [A-Za-z0-9_-]") {
        val root = tempdir().toPath()
        ArcadeHost.open(root, engineVersion = "test").use { h ->
            listOf("", "../x", "a b", "a;drop").forEach { shouldThrow<IllegalArgumentException> { h.register(it, 1) } }
            h.register("ok_name-1", 1)
        }
    }
})
