package dev.sophi.core.tools

import dev.sophi.ai.api.ToolDefinition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith

class ToolRegistryTest : FunSpec({

    fun echoTool(name: String, description: String = "Echo tool") = object : Tool {
        override val name = name
        override val description = description
        override val parametersJson = """{"type":"object","properties":{"input":{"type":"string"}}}"""
        override suspend fun execute(argumentsJson: String) = "echo: $argumentsJson"
    }

    test("empty registry has no tools") {
        val registry = ToolRegistry()
        registry.names().shouldBeEmpty()
        registry.definitions().shouldBeEmpty()
    }

    test("register and get returns the registered tool") {
        val registry = ToolRegistry()
        val tool = echoTool("echo")
        registry.register(tool)
        registry.get("echo") shouldBe tool
    }

    test("get throws NoSuchElementException for unknown tool") {
        val registry = ToolRegistry()
        shouldThrow<NoSuchElementException> { registry.get("unknown") }
    }

    test("getOrNull returns null for unknown tool") {
        val registry = ToolRegistry()
        registry.getOrNull("unknown") shouldBe null
    }

    test("getOrNull returns tool when registered") {
        val registry = ToolRegistry()
        val tool = echoTool("echo")
        registry.register(tool)
        registry.getOrNull("echo") shouldNotBe null
    }

    test("definitions() returns ToolDefinition for each registered tool") {
        val registry = ToolRegistry()
        registry.register(echoTool("tool-a", "A tool"))
        registry.register(echoTool("tool-b", "B tool"))
        val defs = registry.definitions()
        defs shouldHaveSize 2
        val names = defs.map { it.name }.sorted()
        names shouldContainExactly listOf("tool-a", "tool-b")
    }

    test("definitions() maps description and parametersJson correctly") {
        val registry = ToolRegistry()
        registry.register(echoTool("my-tool", "Does things"))
        val def = registry.definitions().first()
        def.name shouldBe "my-tool"
        def.description shouldBe "Does things"
        def.parametersJson shouldBe """{"type":"object","properties":{"input":{"type":"string"}}}"""
    }

    test("names() returns sorted list of registered tool names") {
        val registry = ToolRegistry()
        registry.register(echoTool("zebra"))
        registry.register(echoTool("alpha"))
        registry.register(echoTool("mango"))
        registry.names() shouldContainExactly listOf("alpha", "mango", "zebra")
    }

    test("register returns this for fluent chaining") {
        val registry = ToolRegistry()
        val result = registry.register(echoTool("a")).register(echoTool("b"))
        result shouldBe registry
        registry.names() shouldContainExactly listOf("a", "b")
    }

    test("subset() returns a registry containing only the named tools") {
        val registry = ToolRegistry()
        registry.register(echoTool("read_file"))
        registry.register(echoTool("write_file"))
        registry.register(echoTool("delegate_to_subagent"))

        val scoped = registry.subset(listOf("read_file"))

        scoped.names() shouldContainExactly listOf("read_file")
    }

    test("subset() silently ignores names not present in the source registry") {
        val registry = ToolRegistry()
        registry.register(echoTool("read_file"))

        val scoped = registry.subset(listOf("read_file", "does_not_exist"))

        scoped.names() shouldContainExactly listOf("read_file")
    }

    test("subset() returns an empty registry when given an empty list") {
        val registry = ToolRegistry()
        registry.register(echoTool("read_file"))

        registry.subset(emptyList()).names().shouldBeEmpty()
    }

    test("unregister removes a previously registered tool") {
        val registry = ToolRegistry()
        registry.register(echoTool("echo"))
        registry.unregister("echo")
        registry.getOrNull("echo") shouldBe null
        registry.names().shouldBeEmpty()
    }

    test("unregister on an unknown name is a no-op, does not throw") {
        val registry = ToolRegistry()
        registry.register(echoTool("echo"))
        registry.unregister("does-not-exist")
        registry.names() shouldContainExactly listOf("echo")
    }

    test("unregister returns this for fluent chaining") {
        val registry = ToolRegistry()
        registry.register(echoTool("a")).register(echoTool("b"))
        val result = registry.unregister("a")
        result shouldBe registry
        registry.names() shouldContainExactly listOf("b")
    }

    fun riskyTool(name: String, tier: RiskLevel) = object : Tool {
        override val name = name
        override val description = "risky"
        override val parametersJson = "{}"
        override fun riskLevel(argumentsJson: String) = tier
        override suspend fun execute(argumentsJson: String) = "ran"
    }

    test("safeGrantsFrom keeps only names that probe SAFE with empty arguments") {
        val registry = ToolRegistry()
            .register(riskyTool("safe_tool", RiskLevel.SAFE))
            .register(riskyTool("caution_tool", RiskLevel.CAUTION))
            .register(riskyTool("destructive_tool", RiskLevel.DESTRUCTIVE))

        registry.safeGrantsFrom(listOf("safe_tool", "caution_tool", "destructive_tool")) shouldBe setOf("safe_tool")
    }

    test("safeGrantsFrom drops names not present in the registry") {
        val registry = ToolRegistry().register(riskyTool("safe_tool", RiskLevel.SAFE))

        registry.safeGrantsFrom(listOf("safe_tool", "does_not_exist")) shouldBe setOf("safe_tool")
    }

    test("safeGrantsFrom returns an empty set for a null or empty list") {
        val registry = ToolRegistry().register(riskyTool("safe_tool", RiskLevel.SAFE))

        registry.safeGrantsFrom(null) shouldBe emptySet()
        registry.safeGrantsFrom(emptyList()) shouldBe emptySet()
    }

    test("worstRiskAmong reports the worst tier among the given names") {
        val registry = ToolRegistry()
            .register(riskyTool("safe_tool", RiskLevel.SAFE))
            .register(riskyTool("caution_tool", RiskLevel.CAUTION))
            .register(riskyTool("destructive_tool", RiskLevel.DESTRUCTIVE))

        registry.worstRiskAmong(listOf("safe_tool")) shouldBe RiskLevel.SAFE
        registry.worstRiskAmong(listOf("safe_tool", "caution_tool")) shouldBe RiskLevel.CAUTION
        registry.worstRiskAmong(listOf("safe_tool", "caution_tool", "destructive_tool")) shouldBe RiskLevel.DESTRUCTIVE
    }

    test("worstRiskAmong ignores names not present in the registry and defaults to SAFE for null or empty") {
        val registry = ToolRegistry().register(riskyTool("destructive_tool", RiskLevel.DESTRUCTIVE))

        registry.worstRiskAmong(listOf("does_not_exist")) shouldBe RiskLevel.SAFE
        registry.worstRiskAmong(null) shouldBe RiskLevel.SAFE
        registry.worstRiskAmong(emptyList()) shouldBe RiskLevel.SAFE
    }

    fun fakeTool(name: String, deferred: Boolean = false) = object : Tool {
        override val name = name
        override val description = "d"
        override val parametersJson = "{}"
        override val deferred = deferred
        override suspend fun execute(argumentsJson: String) = ""
    }

    fun deferredRegistry(withSearch: Boolean) = ToolRegistry().also {
        it.register(fakeTool("read_file"))
        it.register(fakeTool("gh__create_issue", deferred = true))
        it.register(fakeTool("gh__list_prs", deferred = true))
        if (withSearch) it.register(ToolSearchTool(it))
    }

    test("definitions hides deferred tools unless activated") {
        val registry = deferredRegistry(withSearch = true)
        registry.definitions().map { it.name } shouldBe listOf("read_file", "tool_search")
        registry.definitions(setOf("gh__list_prs")).map { it.name } shouldBe
            listOf("read_file", "gh__list_prs", "tool_search")
    }

    test("definitions sends deferred tools when tool_search is absent") {
        deferredRegistry(withSearch = false).definitions().map { it.name } shouldContain "gh__create_issue"
    }

    test("subset keeps the deferred flag") {
        deferredRegistry(withSearch = true).subset(listOf("gh__list_prs")).get("gh__list_prs").deferred shouldBe true
    }

    test("a subset's tool_search only sees the subset's own deferred tools") {
        val search = deferredRegistry(withSearch = true)
            .subset(listOf("read_file", "tool_search", "gh__list_prs")).get("tool_search")
        search.description shouldEndWith "Not loaded yet: gh__list_prs"
        kotlinx.coroutines.runBlocking { search.execute("""{"query":"create"}""") } shouldBe
            "No deferred tool matches \"create\". Deferred tools: gh__list_prs"
    }

    test("deferredNames is sorted") {
        deferredRegistry(withSearch = true).deferredNames() shouldBe listOf("gh__create_issue", "gh__list_prs")
    }
})
