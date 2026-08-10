package dev.sophi.companion.ui

import androidx.compose.material3.lightColorScheme
import dev.sophi.companion.SessionState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SophiThemeTest : FunSpec({
    val colors = lightColorScheme()

    test("Idle maps to onSurfaceVariant") {
        statusColor(SessionState.Idle, colors) shouldBe colors.onSurfaceVariant
    }

    test("Running maps to the green active color") {
        statusColor(SessionState.Running, colors) shouldBe SessionActiveColor
    }

    test("NeedsConfirmation maps to the orange attention color") {
        statusColor(SessionState.NeedsConfirmation(emptyList()), colors) shouldBe SessionAttentionColor
    }

    test("Error maps to error") {
        statusColor(SessionState.Error("boom"), colors) shouldBe colors.error
    }
})
