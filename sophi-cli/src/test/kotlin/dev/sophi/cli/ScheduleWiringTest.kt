package dev.sophi.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class ScheduleWiringTest : FunSpec({

    test("loadAgentDefinitionsOrWarn returns an empty list and warns on stderr when a definition file is malformed") {
        val agentsDir = createTempDirectory("agents-test")
        agentsDir.resolve("broken.md").writeText("not a valid agent definition")

        val captured = ByteArrayOutputStream()
        val originalErr = System.err
        System.setErr(PrintStream(captured))
        val result = try {
            loadAgentDefinitionsOrWarn(agentsDir)
        } finally {
            System.setErr(originalErr)
        }

        result.shouldBeEmpty()
        captured.toString() shouldContain "Warning"
        captured.toString() shouldContain agentsDir.toString()
    }

    test("loadAgentDefinitionsOrWarn returns an empty list without warning when the directory is simply empty") {
        val agentsDir = createTempDirectory("agents-test-empty")

        val captured = ByteArrayOutputStream()
        val originalErr = System.err
        System.setErr(PrintStream(captured))
        val result = try {
            loadAgentDefinitionsOrWarn(agentsDir)
        } finally {
            System.setErr(originalErr)
        }

        result.shouldBeEmpty()
        captured.toString() shouldBe ""
    }
})
