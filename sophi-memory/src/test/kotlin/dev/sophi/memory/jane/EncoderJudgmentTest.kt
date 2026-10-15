package dev.sophi.memory.jane

import dev.sophi.ai.api.LLMProvider
import dev.sophi.memory.TurnObservation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path

/** Golden files pin the production prompt: regenerate only on purpose, with GOLDEN_WRITE=1. */
internal fun golden(name: String, actual: String) {
    val path = Path.of("src/test/resources/golden/$name")
    if (System.getenv("GOLDEN_WRITE") == "1") {
        Files.createDirectories(path.parent)
        Files.writeString(path, actual)
    }
    actual shouldBe Files.readString(path)
}

internal val goldenRecent = Memory(
    "mem_d1", "The user's daughter Mila has violin lessons on Thursdays", Room.ENTITIES, 0.6,
    SalienceSignals(rep = 0.1, emph = 0.0, nov = 0.0, aff = 0.0, rec = 1.0),
    Sensitivity.PERSONAL, Provenance.USER_DIRECT, 0L, 0L, "s"
)
internal val goldenChat = TurnObservation("s1", "Mila's violin moved to Tuesdays.", "Updated.", 1_000L)
internal val goldenAmbient = TurnObservation("s1", "so at my company I lead five data engineers", "", 1_000L, ambient = true)

class EncoderJudgmentTest : FunSpec({
    val encoder = SignificanceEncoder(mockk<LLMProvider>(), JanesPalaceConfig(sessionModel = "m"))

    test("the production encoder prompt is pinned (chat, one recent memory)") {
        golden("encoder-chat.txt", encoder.buildPrompt(goldenChat, listOf(goldenRecent)))
    }

    test("the production encoder prompt is pinned (ambient, no recent memories)") {
        golden("encoder-ambient.txt", encoder.buildPrompt(goldenAmbient, emptyList()))
    }
})
