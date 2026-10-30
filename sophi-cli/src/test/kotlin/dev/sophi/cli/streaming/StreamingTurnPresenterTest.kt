package dev.sophi.cli.streaming

import dev.sophi.core.agent.TurnEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class StreamingTurnPresenterTest : FunSpec({
    test("a parallel tool's line waits until the question is answered") {
        val p = StreamingTurnPresenter()
        p.feed(TurnEvent.ToolCallStarted("ask_user", "{}"))
        p.feed(TurnEvent.ToolCallStarted("bash", "{}"))
        p.feed(TurnEvent.ToolCallFinished("bash", "ok")) shouldBe null
        val after = p.feed(TurnEvent.ToolCallFinished("ask_user", "User chose A"))
        after.shouldBeInstanceOf<StreamingTurnPresenter.Rendered.ToolLine>()
        after.text shouldContain "bash"
        after.text shouldContain "ask_user"
    }

    test("ask_user pauses the live region like a confirmation until it finishes") {
        val p = StreamingTurnPresenter()
        p.feed(TurnEvent.ToolCallStarted("ask_user", "{}"))
        p.confirmationPending shouldBe true
        p.feed(TurnEvent.ToolCallFinished("ask_user", "User chose A"))
        p.confirmationPending shouldBe false
        p.feed(TurnEvent.ToolCallStarted("bash", "{}"))
        p.confirmationPending shouldBe false
    }

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
