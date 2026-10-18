package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private class KeyCommand(env: Map<String, String>) : CliktCommand() {
    val key by apiKeyOption()
    init { context { envvarReader = { env[it] } } }
    override fun run() = Unit
}

class ApiKeyOptionTest : FunSpec({
    test("--api-key falls back to SOPHI_API_KEY, so the key stays out of the process list") {
        KeyCommand(mapOf("SOPHI_API_KEY" to "from-env")).apply { parse(emptyList()) }.key shouldBe "from-env"
    }

    test("an explicit --api-key wins over SOPHI_API_KEY") {
        KeyCommand(mapOf("SOPHI_API_KEY" to "from-env")).apply { parse(listOf("--api-key", "flag")) }.key shouldBe "flag"
    }

    test("OPENAI_API_KEY is never picked up: it would be sent to whatever --base-url points at") {
        KeyCommand(mapOf("OPENAI_API_KEY" to "real-openai-key")).apply { parse(emptyList()) }.key shouldBe null
    }
})
