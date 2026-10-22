package dev.sophi.ai.providers

internal data class Split(val content: String, val reasoning: String)

/** Length of the longest suffix of [text] that is a proper prefix of [marker]. */
internal fun partialSuffix(text: String, marker: String): Int =
    (minOf(text.length, marker.length - 1) downTo 1).firstOrNull { text.endsWith(marker.substring(0, it)) } ?: 0

/**
 * Local models often put their reasoning inline as `<think>...</think>` in the content stream.
 * Splits it out, holding back a trailing fragment that might be the start of a marker.
 */
internal class ThinkTagSplitter {
    private var inThink = false
    private var held = ""

    fun feed(chunk: String): Split {
        var buf = held + chunk
        held = ""
        val content = StringBuilder()
        val reasoning = StringBuilder()
        while (true) {
            val marker = if (inThink) "</think>" else "<think>"
            val out = if (inThink) reasoning else content
            val at = buf.indexOf(marker)
            if (at >= 0) {
                out.append(buf, 0, at)
                buf = buf.substring(at + marker.length)
                inThink = !inThink
            } else {
                val keep = partialSuffix(buf, marker)
                out.append(buf, 0, buf.length - keep)
                held = buf.substring(buf.length - keep)
                break
            }
        }
        return Split(content.toString(), reasoning.toString())
    }

    fun flush(): Split {
        val rest = held
        held = ""
        return if (inThink) Split("", rest) else Split(rest, "")
    }
}
