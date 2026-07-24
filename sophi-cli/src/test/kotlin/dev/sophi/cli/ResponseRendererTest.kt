package dev.sophi.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ResponseRendererTest : FunSpec({
    test("renderText() leaves plain text unchanged") {
        ResponseRenderer.renderText("just plain text") shouldBe "just plain text"
    }

    test("renderText() bolds **marked** spans") {
        ResponseRenderer.renderText("this is **important**") shouldBe
            "this is ${TextStyles.bold("important")}"
    }

    test("renderText() dims each line inside a fenced code block") {
        val raw = "before\n```\nline one\nline two\n```\nafter"
        val expected = "before\n" +
            "${TextColors.gray("│ ")}line one\n" +
            "${TextColors.gray("│ ")}line two\n" +
            "after"
        ResponseRenderer.renderText(raw) shouldBe expected
    }

    test("renderToolCall() renders a header, args line, and result line") {
        val rendered = ResponseRenderer.renderToolCall("calculator", "{\"x\":6}", "42")
        val expected = listOf(
            (TextColors.cyan + TextStyles.bold)("⚙ calculator"),
            TextColors.gray("  args: {\"x\":6}"),
            TextColors.gray("  → ") + "42"
        ).joinToString("\n")
        rendered shouldBe expected
    }

    test("renderToolCall() truncates a multi-line result to its first line") {
        val rendered = ResponseRenderer.renderToolCall("cmd", "{}", "line one\nline two")
        rendered shouldBe listOf(
            (TextColors.cyan + TextStyles.bold)("⚙ cmd"),
            TextColors.gray("  args: {}"),
            TextColors.gray("  → ") + "line one"
        ).joinToString("\n")
    }

    test("renderReasoning() renders a dimmed thought-bubble line") {
        val rendered = ResponseRenderer.renderReasoning("first I'll check the docs")
        rendered shouldBe TextStyles.dim("💭 first I'll check the docs")
    }
})
