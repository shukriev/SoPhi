package dev.sophi.cli

import dev.sophi.core.agent.TurnInbox
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SteerEditorTest : FunSpec({
    fun SteerEditor.type(text: String) = text.forEach { key(TurnKey.Text(it)) }

    test("Enter queues a steer, Alt+Enter a follow-up, blank does nothing") {
        val inbox = TurnInbox()
        val editor = SteerEditor(inbox)

        editor.key(TurnKey.Enter)
        editor.type("  ")
        editor.key(TurnKey.AltEnter)
        inbox.snapshot() shouldBe emptyList()
        editor.key(TurnKey.Backspace); editor.key(TurnKey.Backspace)

        editor.type("go left")
        editor.key(TurnKey.Enter)
        editor.type("then tests")
        editor.key(TurnKey.AltEnter)

        inbox.snapshot() shouldBe listOf(TurnInbox.Queued("go left", false), TurnInbox.Queued("then tests", true))
    }

    test("render lists the queue then the line being typed") {
        val editor = SteerEditor(TurnInbox())
        editor.render() shouldBe ""

        editor.type("a"); editor.key(TurnKey.Enter)
        editor.type("b"); editor.key(TurnKey.AltEnter)
        editor.type("cd"); editor.key(TurnKey.Backspace)

        editor.render() shouldBe "  ↳ a\n  ⏎ b (next turn)\n› c"
    }

    test("a pasted newline stays in the line and shows as one line") {
        val inbox = TurnInbox()
        val editor = SteerEditor(inbox)

        editor.type("one\ntwo")
        editor.render() shouldBe "› one ↵ two"

        editor.key(TurnKey.Enter)
        inbox.snapshot() shouldBe listOf(TurnInbox.Queued("one\ntwo", false))
        editor.render() shouldBe "  ↳ one ↵ two"
    }

    test("Backspace drops an emoji typed as a surrogate pair whole") {
        val editor = SteerEditor(TurnInbox())
        editor.type("x😀")
        editor.key(TurnKey.Backspace)
        editor.render() shouldBe "› x"
        editor.key(TurnKey.Backspace)
        editor.key(TurnKey.Backspace)
        editor.render() shouldBe ""
    }

    test("takeDraft returns unsent queue and buffer in order, then empty") {
        val inbox = TurnInbox()
        val editor = SteerEditor(inbox)
        editor.type("steer"); editor.key(TurnKey.Enter)
        editor.type("follow"); editor.key(TurnKey.AltEnter)
        editor.type("half")

        editor.takeDraft() shouldBe "steer\nfollow\nhalf"
        editor.takeDraft() shouldBe ""
        inbox.snapshot() shouldBe emptyList()
    }
})
