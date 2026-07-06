package dev.sophi.cli

class LiveRegion(
    private val out: Appendable,
    private val width: () -> Int
) {
    private var lastLineCount = 0

    fun update(text: String) {
        val lines = wrap(text, width())
        repositionForRewrite()
        out.append(lines.joinToString("\n"))
        flushIfPossible()
        lastLineCount = lines.size
    }

    fun clear() {
        repositionForRewrite()
        flushIfPossible()
        lastLineCount = 0
    }

    private fun repositionForRewrite() {
        if (lastLineCount == 0) return
        out.append("\r")
        if (lastLineCount > 1) out.append("[${lastLineCount - 1}A")
        out.append("[0J")
    }

    private fun flushIfPossible() {
        if (out is java.io.Flushable) out.flush()
    }

    private fun wrap(text: String, width: Int): List<String> {
        val safeWidth = width.coerceAtLeast(1)
        return text.split("\n")
            .flatMap { line -> if (line.isEmpty()) listOf("") else line.chunked(safeWidth) }
            .ifEmpty { listOf("") }
    }
}
