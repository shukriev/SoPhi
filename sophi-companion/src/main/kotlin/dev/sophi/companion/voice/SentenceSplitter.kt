package dev.sophi.companion.voice

/**
 * Buffers streamed text and emits complete sentences as boundaries arrive. Not thread-safe —
 * [dev.sophi.companion.voice.VoiceController] only ever calls it from its own single-threaded
 * token-processing path.
 *
 * A sentence boundary is `.`/`!`/`?`/newline immediately followed by whitespace. Punctuation at
 * the very end of the buffer (no trailing whitespace seen yet) is NOT treated as a boundary,
 * since more text may still be coming — this means abbreviations ("Dr.") and decimals ("3.14")
 * can produce a false split once whitespace follows them. Accepted as a known, minor limitation:
 * a mis-timed sentence boundary just makes Piper's pacing slightly off, not wrong.
 */
class SentenceSplitter {
    private val buffer = StringBuilder()

    fun onToken(text: String): List<String> {
        buffer.append(text)
        val sentences = mutableListOf<String>()
        while (true) {
            val boundary = findBoundary() ?: break
            val sentence = buffer.substring(0, boundary).trim()
            if (sentence.isNotEmpty()) sentences.add(sentence)
            buffer.delete(0, boundary)
        }
        return sentences
    }

    fun flush(): String? {
        val remainder = buffer.toString().trim()
        buffer.clear()
        return remainder.ifEmpty { null }
    }

    private fun findBoundary(): Int? {
        for (i in buffer.indices) {
            val c = buffer[i]
            if ((c == '.' || c == '!' || c == '?' || c == '\n') &&
                i + 1 < buffer.length && buffer[i + 1].isWhitespace()
            ) {
                return i + 1
            }
        }
        return null
    }
}
