package dev.sophi.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import dev.sophi.sdk.ModelCatalog
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.io.path.createTempFile
import kotlin.io.path.writeBytes

class ModelsCommandTest : FunSpec({
    val catalog = ModelCatalog.parse(
        """{"anthropic":{"claude-x":{"c":200000,"i":3,"p":15,"t":1,"x":1}},"openai":{"gpt-y":{"c":128000}}}"""
    )

    test("sophi models <query> lists matching entries with prices and capabilities") {
        val out = ModelsCommand(catalog = { catalog }).test("claude").output
        out shouldContain "anthropic/claude-x"
        out shouldContain "$3.00/$15.00"
        out shouldContain "tools"
        out shouldNotContain "gpt-y"
    }

    test("an entry without a price shows a dash") {
        ModelsCommand(catalog = { catalog }).test("gpt").output shouldContain "-/-"
    }

    test("sophi models inspect <file> prints the GGUF header; the query form still works beside it") {
        // general.architecture = "llama", nothing else: enough to show the command delegates.
        val bytes = java.nio.ByteBuffer.allocate(4 + 4 + 8 + 8 + 8 + 20 + 4 + 8 + 5).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("GGUF".toByteArray()).putInt(3).putLong(0).putLong(1)
            .putLong(20).put("general.architecture".toByteArray()).putInt(8).putLong(5).put("llama".toByteArray()).array()
        val gguf = createTempFile("m", ".gguf").also { it.writeBytes(bytes) }
        fun models() = ModelsCommand(catalog = { catalog }).subcommands(ModelsInspectCommand())
        models().test("inspect $gguf").output shouldContain "architecture:   llama"
        models().test("claude").output shouldContain "anthropic/claude-x"

        val bad = createTempFile("m", ".gguf").also { it.writeBytes("nope".toByteArray()) }
        val result = models().test("inspect $bad")
        result.statusCode shouldBe 1
        result.stderr shouldContain "not a GGUF file"
    }
})
