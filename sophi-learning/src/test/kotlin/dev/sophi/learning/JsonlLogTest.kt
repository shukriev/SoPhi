package dev.sophi.learning

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.attribute.PosixFilePermissions

class JsonlLogTest : FunSpec({
    test("append then readAll round-trips lines and skips blank/torn lines") {
        val path = tempdir().toPath().resolve("events.jsonl")
        val log = JsonlLog(path)
        log.append("""{"a":1}""")
        // Inject a blank line directly into the file to exercise the skip-blank filter.
        Files.write(path, "\n".toByteArray(), APPEND)
        log.append("""{"a":2}""")
        log.readAll() shouldBe listOf("""{"a":1}""", """{"a":2}""")
        log.readLast(1) shouldBe listOf("""{"a":2}""")
    }

    test("a new log is readable by its owner only") {
        val path = tempdir().toPath().resolve("events.jsonl")
        JsonlLog(path).append("""{"a":1}""")
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path)) shouldBe "rw-------"
    }

    test("an existing world-readable log is tightened on the first append") {
        val path = tempdir().toPath().resolve("events.jsonl")
        Files.writeString(path, "{\"a\":1}\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
        JsonlLog(path).append("""{"a":2}""")
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path)) shouldBe "rw-------"
    }

    test("readAll on a missing file returns empty") {
        JsonlLog(tempdir().toPath().resolve("nope.jsonl")).readAll() shouldBe emptyList()
    }
})
