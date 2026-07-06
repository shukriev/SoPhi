package dev.sophi.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles

object ResponseRenderer {
    private val codeFenceRegex = Regex("```[a-zA-Z0-9]*\\n([\\s\\S]*?)```")
    private val boldRegex = Regex("\\*\\*(.+?)\\*\\*")

    fun renderText(raw: String): String {
        var text = codeFenceRegex.replace(raw) { match ->
            match.groupValues[1].trimEnd('\n')
                .lineSequence()
                .joinToString("\n") { line -> TextColors.gray("│ ") + line }
        }
        text = boldRegex.replace(text) { match -> TextStyles.bold(match.groupValues[1]) }
        return text
    }

    fun renderToolCall(name: String, argsJson: String, result: String): String {
        val header = (TextColors.cyan + TextStyles.bold)("⚙ $name")
        val argsLine = TextColors.gray("  args: $argsJson")
        val firstResultLine = result.lineSequence().first()
        val resultLine = TextColors.gray("  → ") + firstResultLine
        return listOf(header, argsLine, resultLine).joinToString("\n")
    }
}
