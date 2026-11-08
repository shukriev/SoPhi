package dev.sophi.ai.providers

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.writeBytes

/** Builds GGUF bytes little-endian, the way llama.cpp writes them. */
private class GgufBytes {
    private val out = ByteArrayOutputStream()
    private fun le(n: Int, fill: ByteBuffer.() -> Unit) =
        out.write(ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array())
    fun u32(v: Int) = apply { le(4) { putInt(v) } }
    fun u64(v: Long) = apply { le(8) { putLong(v) } }
    fun str(s: String) = apply { val b = s.toByteArray(); u64(b.size.toLong()); out.write(b) }
    fun raw(b: ByteArray) = apply { out.write(b) }
    fun kvString(k: String, v: String) = str(k).u32(8).str(v)
    fun kvU32(k: String, v: Int) = str(k).u32(4).u32(v)
    fun kvU64(k: String, v: Long) = str(k).u32(10).u64(v)
    fun bytes(): ByteArray = out.toByteArray()
}

private fun header(kvCount: Long, version: Int = 3) =
    GgufBytes().raw("GGUF".toByteArray()).u32(version).u64(291).u64(kvCount)

private fun file(bytes: ByteArray): Path = createTempFile("t", ".gguf").also { it.writeBytes(bytes) }

private val TEMPLATE = "{% for message in messages %}\n{{ message.content }}\n{% endfor %}"

private fun syntheticGguf(): ByteArray = header(8)
    .kvString("general.architecture", "qwen3")
    .kvU32("general.file_type", 15)
    .kvU64("general.parameter_count", 8_190_735_360L)
    .kvU32("qwen3.context_length", 40960)
    // A vocab-sized string array plus a numeric array: both must be skipped.
    .str("tokenizer.ggml.tokens").u32(9).u32(8).u64(20_000).apply { repeat(20_000) { str("tok$it") } }
    .str("tokenizer.ggml.scores").u32(9).u32(6).u64(20_000).raw(ByteArray(80_000))
    .kvString("tokenizer.chat_template", TEMPLATE)
    .str("general.flag").u32(7).raw(byteArrayOf(1))
    .raw(ByteArray(64)) // tensor info would start here; never read
    .bytes()

class GgufTest : FunSpec({
    test("reads architecture, quantization, parameters, context and chat template from the header") {
        val info = readGgufHeader(file(syntheticGguf()))
        info.version shouldBe 3
        info.architecture shouldBe "qwen3"
        info.contextLength shouldBe 40960L
        info.describe() shouldBe listOf(
            "architecture:   qwen3",
            "quantization:   Q4_K_M",
            "parameters:     8190735360",
            "context_length: 40960",
            "chat template:  ${TEMPLATE.length} chars, first line: {% for message in messages %}",
        )
    }

    test("missing keys print as unknown or absent") {
        val info = readGgufHeader(file(header(1, version = 2).kvString("general.architecture", "llama").bytes()))
        info.describe() shouldBe listOf(
            "architecture:   llama",
            "quantization:   unknown",
            "parameters:     unknown",
            "context_length: unknown",
            "chat template:  none",
        )
    }

    test("a truncated file is a clear error at every cut point") {
        val bytes = syntheticGguf()
        for (cut in listOf(0, 3, 10, 30, 60, 200, 100_000, bytes.size - 120)) {
            shouldThrow<GgufException> { readGgufHeader(file(bytes.copyOf(cut))) }
        }
    }

    test("not a GGUF file") {
        shouldThrow<GgufException> { readGgufHeader(file("hello world, not a model".toByteArray())) }
            .message shouldContain "not a GGUF"
    }

    test("an unsupported version is refused") {
        shouldThrow<GgufException> { readGgufHeader(file(header(0, version = 1).bytes())) }
            .message shouldContain "version 1"
    }

    test("a huge declared length fails on the file size instead of allocating") {
        val hugeKey = header(1).u64(Long.MAX_VALUE - 1).bytes()
        shouldThrow<GgufException> { readGgufHeader(file(hugeKey)) }.message shouldContain "truncated"
        val hugeArray = header(1).str("a").u32(9).u32(10).u64(Long.MAX_VALUE / 2).bytes()
        shouldThrow<GgufException> { readGgufHeader(file(hugeArray)) }.message shouldContain "truncated"
        val negative = header(1).str("a").u32(8).u64(-1).bytes()
        shouldThrow<GgufException> { readGgufHeader(file(negative)) }
    }

    test("an unknown value type is an error") {
        shouldThrow<GgufException> { readGgufHeader(file(header(1).str("a").u32(99).bytes())) }
            .message shouldContain "value type 99"
    }
})
