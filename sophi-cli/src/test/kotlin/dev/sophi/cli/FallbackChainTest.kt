package dev.sophi.cli

import dev.sophi.ai.api.CompletionRequest
import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.StreamEvent
import dev.sophi.ai.providers.LocalServer
import dev.sophi.ai.providers.LocalServerKind
import dev.sophi.ai.providers.ProviderConfigException
import dev.sophi.sdk.ModelCatalog
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

private val CATALOG = ModelCatalog.parse("""{"anthropic":{"claude-x":{"c":123456,"i":3,"p":15,"t":1}}}""")

private val SERVER = LocalServer(LocalServerKind.LM_STUDIO, "http://127.0.0.1:1234/v1", listOf("a", "b"))
private val LOCAL = ResolvedProvider("local", SERVER.baseUrl, "a", 32_000, null, emptyList(), server = SERVER)

private class Named(override val name: String) : LLMProvider {
    override suspend fun complete(request: CompletionRequest): LLMResponse = error("unused")
    override fun stream(request: CompletionRequest): Flow<StreamEvent> = emptyFlow()
}

class FallbackChainTest : FunSpec({
    val warnings = mutableListOf<String>()
    val built = mutableListOf<String>()
    beforeTest { warnings.clear(); built.clear() }
    fun chain(spec: String, probe: (String) -> LocalServer? = { null }) = fallbackChain(
        spec, LOCAL, CATALOG, warnings::add,
        build = { type, url, model -> built += "$type $url $model"; Named(type) },
        probe = probe, detect = { _, _ -> 4_096 },
    )

    test("no chain: nothing is built or probed") {
        chain("").shouldBeEmpty()
        built.shouldBeEmpty()
    }

    test("a bare name is another model on the session's own server, with that server's window for it") {
        val c = chain("b")
        c.single().model shouldBe "b"
        c.single().contextWindowTokens shouldBe 4_096
        c.single().provider shouldBe null
        chain("zzz").shouldBeEmpty()
        warnings.single() shouldContain "zzz isn't on the server"
    }

    test("name@url is a model on another server on this machine or network, never billed") {
        val other = LocalServer(null, "http://127.0.0.1:11434/v1", listOf("q"))
        val c = chain("q@http://127.0.0.1:11434/v1") { other }.single()
        c.contextWindowTokens shouldBe 4_096
        c.price shouldBe null
        built shouldBe listOf("local http://127.0.0.1:11434/v1 q")
    }

    test("a server that's down at start still gets its entry, with a small window assumed") {
        chain("q@http://127.0.0.1:11434/v1").single().contextWindowTokens shouldBe 8192
        warnings.single() shouldContain "8192"
    }

    test("a public URL is refused: the only cloud fallback is one named @claude") {
        chain("gpt@https://api.example.com/v1").shouldBeEmpty()
        warnings.single() shouldContain "@claude"
        built.shouldBeEmpty()
    }

    test("name@claude is Anthropic's API, priced from the catalog") {
        val c = chain("b, claude-x@claude").also { it.size shouldBe 2 }[1]
        c.price!!.inputUsdPerM shouldBe 3.0
        c.contextWindowTokens shouldBe 123456
        built shouldBe listOf("claude null claude-x")
    }

    test("a cloud model the catalog doesn't know, or one without a key, is left out with a warning") {
        chain("claude-typo@claude").shouldBeEmpty()
        warnings.single() shouldContain "catalog"
        val noKey = fallbackChain("claude-x@claude", LOCAL, CATALOG, warnings::add, build = { _, _, _ -> throw ProviderConfigException("no key") })
        noKey.shouldBeEmpty()
        warnings.last() shouldContain "no key"
    }
})
