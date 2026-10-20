package dev.sophi.memory.jane

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.system1.Judgments
import dev.sophi.memory.TurnObservation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

    val chatInput = """{"turn":{"userInput":"Mila's violin moved to Tuesdays.","assistantReply":"Updated."},
        "recent":[{"id":"mem_d1","room":"ENTITIES","text":"The user's daughter Mila has violin lessons on Thursdays"}]}"""

    test("render returns the production prompt with production settings") {
        val req = EncoderJudgment.render(chatInput, "small")
        req.messages.single().content shouldBe Files.readString(Path.of("src/test/resources/golden/encoder-chat.txt"))
        req.model shouldBe "small"
        req.judgment shouldBe Judgments.ENCODER
        req.reasoningEffort shouldBe "none"
        req.temperature shouldBe 0.0
        req.maxTokens shouldBe 1024
    }

    test("render of an ambient input matches the ambient golden") {
        val req = EncoderJudgment.render("""{"turn":{"userInput":"so at my company I lead five data engineers","ambient":true}}""", "m")
        req.messages.single().content shouldBe Files.readString(Path.of("src/test/resources/golden/encoder-ambient.txt"))
    }

    test("render rejects input without a turn") {
        shouldThrow<Exception> { EncoderJudgment.render("{}", "m") }
    }

    test("parse: a valid object becomes canonical JSON with defaults filled in") {
        val out = EncoderJudgment.parse("""{"memories":[{"text":"The user has a dog","room":"ENTITIES"}]}""")!!
        out shouldContain "\"commitment\":false"
        out shouldContain "\"sensitivity\":\"PERSONAL\""
        out shouldContain "\"provenance\":null"
    }

    test("parse: a bare [] is an empty verdict") {
        EncoderJudgment.parse("[]") shouldBe """{"memories":[],"profile":[],"meeting":null}"""
    }

    test("parse: an object without memories has an empty memories list") {
        EncoderJudgment.parse("""{"profile":[]}""")!! shouldContain "\"memories\":[]"
    }

    // Review Focus 2
    test("parse: a reply in a code fence with prose around it parses as production does") {
        val fence = "`".repeat(3)
        EncoderJudgment.parse("Here you go:\n${fence}json\n{\"memories\":[],\"meeting\":0.2}\n$fence")!! shouldContain "\"meeting\":0.2"
    }

    test("parse: empty, prose and wrong-typed replies are rejected") {
        EncoderJudgment.parse("").shouldBeNull()
        EncoderJudgment.parse("not json at all").shouldBeNull()
        EncoderJudgment.parse("""{"memories": 5}""").shouldBeNull()
    }

    test("SignificanceEncoder still sends the production request") {
        val provider = mockk<LLMProvider>()
        val captured = io.mockk.slot<dev.sophi.ai.api.CompletionRequest>()
        io.mockk.coEvery { provider.complete(capture(captured)) } returns
            dev.sophi.ai.api.LLMResponse.Text("[]", dev.sophi.ai.api.TokenUsage(1, 1))
        kotlinx.coroutines.runBlocking {
            SignificanceEncoder(provider, JanesPalaceConfig(sessionModel = "m", encoderMaxTokens = 2048)).encode(goldenChat, listOf(goldenRecent))
        }
        captured.captured.maxTokens shouldBe 2048
        captured.captured.judgment shouldBe Judgments.ENCODER
        captured.captured.messages.single().content shouldBe Files.readString(Path.of("src/test/resources/golden/encoder-chat.txt"))
    }

    // Gemma 4 answers "profile": {} — a strict decode threw away the whole verdict, memories included.
    test("parse: an empty-object profile keeps the memories") {
        val v = parseEncoderVerdict("""{"memories":[{"text":"The user has a dog","room":"ENTITIES"}],"profile":{}}""")!!
        v.memories.single().text shouldBe "The user has a dog"
        v.profile shouldBe emptyList()
    }

    test("parse: a map profile becomes path/value entries") {
        val v = parseEncoderVerdict("""{"memories":[],"profile":{"preferred_response_style":"bulleted","pets":2}}""")!!
        v.profile shouldBe listOf(VerdictProfile("preferred_response_style", "bulleted"), VerdictProfile("pets", "2"))
    }

    test("parse: a profile of any other type is dropped, an array is kept as is") {
        parseEncoderVerdict("""{"memories":[],"profile":"likes tea"}""")!!.profile shouldBe emptyList()
        parseEncoderVerdict("""{"memories":[],"profile":[{"path":"a.b","value":"c","explicit":true}]}""")!!.profile shouldBe
            listOf(VerdictProfile("a.b", "c", true))
    }

    test("parse: profile tolerance does not loosen memories") {
        parseEncoderVerdict("""{"memories":{},"profile":{}}""").shouldBeNull()
    }

    test("the prompt asks for English memory text and defines the sensitivity levels") {
        val prompt = encoder.buildPrompt(goldenChat, emptyList())
        prompt shouldContain "always in English"
        prompt shouldContain "PERSONAL is the default"
        prompt shouldContain "RESTRICTED only when the user asks to keep it private"
    }
})
