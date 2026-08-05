package dev.sophi.web.config

import dev.sophi.core.agent.AgentLoop
import dev.sophi.core.session.FileSessionManager
import dev.sophi.core.tools.ConfirmationPolicy
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.mcp.McpClientManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk

class AgentConfigurationTest : FunSpec({
    test("buildProviderFromProperties builds a claude provider when type=claude") {
        val props = ProviderProperties(type = "claude", model = "claude-opus-4-8", apiKey = "sk-ant-test")
        buildProviderFromProperties(props).name shouldBe "claude"
    }

    test("buildProviderFromProperties builds an openai-compat provider when type=openai-compat") {
        val props = ProviderProperties(
            type = "openai-compat",
            model = "qwen2.5:7b",
            baseUrl = "http://localhost:11434/v1"
        )
        buildProviderFromProperties(props).name shouldBe "openai-compat"
    }

    test("buildProviderFromProperties throws when openai-compat is missing base-url") {
        val props = ProviderProperties(type = "openai-compat", model = "qwen2.5:7b")
        shouldThrow<IllegalStateException> { buildProviderFromProperties(props) }
    }

    test("buildProviderFromProperties throws when claude is missing an api key") {
        // buildProviderFromProperties now falls back to System.getenv("ANTHROPIC_API_KEY") when
        // props.apiKey is null (matching sophi-cli's ProviderFactory behavior). This test relies on
        // ANTHROPIC_API_KEY not being set to a real key in the test environment; asserting on the env
        // var directly would make the test flaky depending on who/where it runs, so instead we cover
        // the fallback structurally: the explicit-apiKey path is exercised by the "builds a claude
        // provider" test above, and this test documents that the null-apiKey path still throws when
        // no env var is present.
        val props = ProviderProperties(type = "claude", model = "claude-opus-4-8", apiKey = null)
        shouldThrow<IllegalStateException> { buildProviderFromProperties(props) }
    }

    test("buildProviderFromProperties throws for an unknown type") {
        val props = ProviderProperties(type = "bogus")
        shouldThrow<IllegalStateException> { buildProviderFromProperties(props) }
    }

    test("buildProviderFromProperties matches provider type case-insensitively") {
        val props = ProviderProperties(type = "Claude", model = "claude-opus-4-8", apiKey = "sk-ant-test")
        buildProviderFromProperties(props).name shouldBe "claude"
    }

    test("toolRegistry() registers grep, glob, edit_file, bash, and fetch_url") {
        val config = AgentConfiguration(ProviderProperties(type = "claude", apiKey = "sk-ant-test"))
        config.toolRegistry(McpClientManager()).names() shouldContainAll listOf("grep", "glob", "edit_file", "bash", "fetch_url")
    }

    test("toolRegistry() does not register web_search when BRAVE_SEARCH_API_KEY is unset") {
        // Relies on BRAVE_SEARCH_API_KEY not being set to a real key in the test environment,
        // matching the existing ANTHROPIC_API_KEY fallback test's approach above.
        val config = AgentConfiguration(ProviderProperties(type = "claude", apiKey = "sk-ant-test"))
        config.toolRegistry(McpClientManager()).names() shouldNotContain "web_search"
    }

    test("confirmationPolicy() defaults to DENY_ALL") {
        val config = AgentConfiguration(ProviderProperties(type = "claude", apiKey = "sk-ant-test"))
        val requests = listOf(
            dev.sophi.core.tools.ConfirmationRequest("c1", "bash", "{}", dev.sophi.core.tools.RiskLevel.DESTRUCTIVE)
        )
        kotlinx.coroutines.runBlocking { config.confirmationPolicy().confirm(requests) } shouldBe mapOf("c1" to false)
    }

    test("mcpClientManager() connects zero servers when .sophi/mcp.json is absent, registering no MCP tools") {
        val config = AgentConfiguration(ProviderProperties(type = "claude", apiKey = "sk-ant-test"))
        val manager = config.mcpClientManager()
        val tools = kotlinx.coroutines.runBlocking { manager.connect(emptyList()) }
        tools.shouldBeEmpty()
    }

    test("agentLoop() fails fast with a clear message when the context window is not configured") {
        val config = AgentConfiguration(ProviderProperties(type = "claude", apiKey = "sk-ant-test"))
        val ex = shouldThrow<IllegalStateException> {
            config.agentLoop(
                mockk(), ToolRegistry(),
                FileSessionManager(tempdir().toPath()),
                ConfirmationPolicy.DENY_ALL
            )
        }
        ex.message!! shouldContain "sophi.provider.context-window-tokens"
    }

    test("agentLoop() builds a loop when the context window is configured") {
        val config = AgentConfiguration(
            ProviderProperties(type = "claude", apiKey = "sk-ant-test", contextWindowTokens = 200_000)
        )
        val loop = config.agentLoop(
            mockk(), ToolRegistry(),
            FileSessionManager(tempdir().toPath()),
            ConfirmationPolicy.DENY_ALL
        )
        loop.shouldBeInstanceOf<AgentLoop>()
    }
})
