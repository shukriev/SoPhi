package dev.sophi.ai.providers

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

class GgufException(message: String) : Exception(message)

/** A GGUF file's header and scalar metadata; array values (the tokenizer vocab) are skipped, not kept. */
class GgufInfo(val version: Int, val tensorCount: Long, val metadata: Map<String, Any>) {
    val architecture: String? get() = metadata["general.architecture"] as? String
    val contextLength: Long? get() = architecture?.let { metadata["$it.context_length"] as? Long }
    val chatTemplate: String? get() = metadata["tokenizer.chat_template"] as? String

    /** `general.file_type` as llama.cpp names it (llama_ftype), e.g. Q4_K_M. */
    val quantization: String?
        get() = (metadata["general.file_type"] as? Long)?.let { FILE_TYPES[it.toInt()] ?: "file type $it" }

    /** `general.parameter_count` when the converter wrote it, else the `general.size_label` such as 8B. */
    val parameters: String?
        get() = (metadata["general.parameter_count"] ?: metadata["general.size_label"])?.toString()

    fun describe(): List<String> = listOf(
        "architecture:   ${architecture ?: "unknown"}",
        "quantization:   ${quantization ?: "unknown"}",
        "parameters:     ${parameters ?: "unknown"}",
        "context_length: ${contextLength ?: "unknown"}",
        "chat template:  " + (chatTemplate?.let { "${it.length} chars, first line: ${it.lineSequence().first()}" } ?: "none"),
    )
}

private val FILE_TYPES = mapOf(
    0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1",
    10 to "Q2_K", 11 to "Q3_K_S", 12 to "Q3_K_M", 13 to "Q3_K_L", 14 to "Q4_K_S", 15 to "Q4_K_M",
    16 to "Q5_K_S", 17 to "Q5_K_M", 18 to "Q6_K", 19 to "IQ2_XXS", 20 to "IQ2_XS", 21 to "Q2_K_S",
    22 to "IQ3_XS", 23 to "IQ3_XXS", 24 to "IQ1_S", 25 to "IQ4_NL", 26 to "IQ3_S", 27 to "IQ3_M",
    28 to "IQ2_S", 29 to "IQ2_M", 30 to "IQ4_XS", 31 to "IQ1_M", 32 to "BF16", 36 to "TQ1_0", 37 to "TQ2_0",
)

/** Byte widths of the fixed-size GGUF value types; 8 (string) and 9 (array) are variable. */
private val FIXED_SIZE = mapOf(0 to 1, 1 to 1, 2 to 2, 3 to 2, 4 to 4, 5 to 4, 6 to 4, 7 to 1, 10 to 8, 11 to 8, 12 to 8)
private const val MAX_KEPT_STRING = 16 shl 20

/**
 * Reads the GGUF (v2/v3) header and metadata key/values of [file] without loading the model:
 * stops before the tensor infos. Every declared length is checked against the file size before it
 * is read or skipped, so a truncated or garbage file throws [GgufException] instead of allocating.
 */
fun readGgufHeader(file: Path): GgufInfo {
    val size = Files.size(file)
    return Files.newInputStream(file).buffered().use { GgufReader(it, size).read() }
}

private class GgufReader(private val input: InputStream, private val size: Long) {
    private var pos = 0L

    fun read(): GgufInfo {
        if (size < 4 || !bytes(4).contentEquals("GGUF".toByteArray())) throw GgufException("not a GGUF file (bad magic)")
        val version = u32()
        if (version != 2L && version != 3L) throw GgufException("unsupported GGUF version $version (only 2 and 3)")
        val tensorCount = u64()
        val kvCount = u64()
        val metadata = LinkedHashMap<String, Any>()
        // Each pair is at least 12 bytes, so a garbage count runs into the file size, not forever.
        for (i in 0 until kvCount) {
            val key = string()
            value(u32().toInt())?.let { metadata[key] = it }
        }
        return GgufInfo(version.toInt(), tensorCount, metadata)
    }

    /** The value of [type]; arrays are skipped and give null. */
    private fun value(type: Int): Any? = when (type) {
        0, 2, 4, 10 -> unsigned(FIXED_SIZE.getValue(type))
        1 -> le(1).get().toLong()
        3 -> le(2).short.toLong()
        5 -> le(4).int.toLong()
        11 -> le(8).long
        6 -> le(4).float.toDouble()
        12 -> le(8).double
        7 -> le(1).get() != 0.toByte()
        8 -> string()
        9 -> null.also { skipArray() }
        else -> throw GgufException("unknown value type $type at byte ${pos - 4}")
    }

    private fun skipArray() {
        val type = u32().toInt()
        val count = u64()
        need(count) // every element is at least one byte
        val width = FIXED_SIZE[type]
        when {
            width != null -> { if (count > (size - pos) / width) truncated(); skip(count * width) }
            type == 8 -> for (i in 0 until count) skip(u64())
            type == 9 -> for (i in 0 until count) skipArray()
            else -> throw GgufException("unknown array element type $type at byte ${pos - 12}")
        }
    }

    private fun string(): String {
        val n = u64()
        need(n)
        if (n > MAX_KEPT_STRING) throw GgufException("string of $n bytes at byte $pos is too long to be metadata")
        return String(bytes(n.toInt()), Charsets.UTF_8)
    }

    private fun u32(): Long = le(4).int.toLong() and 0xFFFFFFFFL
    /** Values above Long.MAX_VALUE come back negative and fail [need]; no real header has them. */
    private fun u64(): Long = le(8).long
    private fun unsigned(width: Int): Long = when (width) {
        1 -> le(1).get().toLong() and 0xFF
        2 -> le(2).short.toLong() and 0xFFFF
        4 -> u32()
        else -> u64()
    }

    private fun le(n: Int): ByteBuffer = ByteBuffer.wrap(bytes(n)).order(ByteOrder.LITTLE_ENDIAN)
    private fun bytes(n: Int): ByteArray { need(n.toLong()); pos += n; return input.readNBytes(n) }
    private fun skip(n: Long) { need(n); input.skipNBytes(n); pos += n }
    private fun need(n: Long) { if (n < 0 || n > size - pos) truncated() }
    private fun truncated(): Nothing = throw GgufException("truncated or corrupt GGUF: a length at byte $pos runs past the end of the file ($size bytes)")
}
