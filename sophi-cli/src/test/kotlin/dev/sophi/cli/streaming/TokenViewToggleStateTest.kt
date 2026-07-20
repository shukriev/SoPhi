package dev.sophi.cli.streaming

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TokenViewToggleStateTest : FunSpec({
    test("initial state is not viewing tokens") {
        val state = TokenViewToggleState()
        state.isViewingTokens shouldBe false
    }

    test("toggle switches state") {
        val state = TokenViewToggleState()
        val toggled = state.toggle()
        toggled.isViewingTokens shouldBe true
    }

    test("toggle is idempotent") {
        val state = TokenViewToggleState()
        val once = state.toggle()
        val twice = once.toggle()
        twice.isViewingTokens shouldBe false
    }
})
