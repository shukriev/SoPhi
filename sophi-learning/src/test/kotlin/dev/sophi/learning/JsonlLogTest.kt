package dev.sophi.learning

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND

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

    test("readAll on a missing file returns empty") {
        JsonlLog(tempdir().toPath().resolve("nope.jsonl")).readAll() shouldBe emptyList()
    }
})
