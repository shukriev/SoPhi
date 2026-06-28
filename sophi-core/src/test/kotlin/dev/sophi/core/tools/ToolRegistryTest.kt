package dev.sophi.core.tools

import dev.sophi.ai.api.ToolDefinition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

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
})
