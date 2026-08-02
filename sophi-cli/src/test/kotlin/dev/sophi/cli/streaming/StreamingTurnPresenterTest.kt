package dev.sophi.cli.streaming

import dev.sophi.core.agent.TurnEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class StreamingTurnPresenterTest : FunSpec({
    test("Token events accumulate into finalText with no Rendered signal") {
        val presenter = StreamingTurnPresenter()
        presenter.feed(TurnEvent.Token("Hello")) shouldBe null
        presenter.feed(TurnEvent.Token(" World")) shouldBe null
        presenter.finalText() shouldBe "Hello World"
    }

    test("ReasoningToken events accumulate into reasoningText") {
        val presenter = StreamingTurnPresenter()
        presenter.feed(TurnEvent.ReasoningToken("thinking"))
        presenter.reasoningText() shouldBe "thinking"
    }

    test("reasoningText is null when nothing was fed") {
        StreamingTurnPresenter().reasoningText() shouldBe null
    }

    test("ConfirmationStarted sets confirmationPending and signals Cleared") {
        val presenter = StreamingTurnPresenter()
        presenter.feed(TurnEvent.ConfirmationStarted(listOf("bash"))) shouldBe StreamingTurnPresenter.Rendered.Cleared
        presenter.confirmationPending shouldBe true
    }

    test("ConfirmationFinished clears confirmationPending and signals Redraw") {
        val presenter = StreamingTurnPresenter()
        presenter.feed(TurnEvent.ConfirmationStarted(listOf("bash")))
        presenter.feed(TurnEvent.ConfirmationFinished) shouldBe StreamingTurnPresenter.Rendered.Redraw
        presenter.confirmationPending shouldBe false
    }

    test("ToolCallFinished returns a rendered tool-call line") {
        val presenter = StreamingTurnPresenter()
        presenter.feed(TurnEvent.ToolCallStarted("grep", "{\"pattern\":\"foo\"}"))
        val rendered = presenter.feed(TurnEvent.ToolCallFinished("grep", "match found", isError = false, durationMillis = 5))
        rendered.shouldBeInstanceOf<StreamingTurnPresenter.Rendered.ToolLine>()
        (rendered as StreamingTurnPresenter.Rendered.ToolLine).text.shouldContain("grep")
    }

    test("toggleTokenView flips the token-view state used by renderFrame") {
        val presenter = StreamingTurnPresenter()
        presenter.feed(TurnEvent.Token("x"))
        val before = presenter.renderFrame()
        presenter.toggleTokenView()
        val after = presenter.renderFrame()
        (before == after) shouldBe false
    }
})
