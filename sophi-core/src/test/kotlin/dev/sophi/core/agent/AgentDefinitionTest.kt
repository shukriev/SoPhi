package dev.sophi.core.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class AgentDefinitionTest : FunSpec({

    val source = Path.of("test.md")

    test("parseAgentDefinition() extracts metadata and system prompt from frontmatter") {
        val md = """
            ---
            name: explore
            description: Read-only codebase search.
            allowedTools:
              - read_file
            ---
            You are a fast, read-only exploration subagent.
        """.trimIndent()

        val definition = parseAgentDefinition(md, source)

        definition.name shouldBe "explore"
        definition.description shouldBe "Read-only codebase search."
        definition.allowedTools shouldBe listOf("read_file")
        definition.systemPrompt shouldBe "You are a fast, read-only exploration subagent."
    }

    test("parseAgentDefinition() defaults model to null when absent") {
        val md = """
            ---
            name: general
            description: General purpose.
            ---
            Do whatever is asked.
        """.trimIndent()

        parseAgentDefinition(md, source).model shouldBe null
    }

    test("parseAgentDefinition() reads model when present") {
        val md = """
            ---
            name: coder
            description: Coding agent.
            model: claude-opus-4-1
            ---
            Write code.
        """.trimIndent()

        parseAgentDefinition(md, source).model shouldBe "claude-opus-4-1"
    }

    test("parseAgentDefinition() defaults allowedTools to empty list when absent") {
        val md = """
            ---
            name: thinker
            description: No tools needed.
            ---
            Just think it through.
        """.trimIndent()

        parseAgentDefinition(md, source).allowedTools shouldBe emptyList()
    }

    test("parseAgentDefinition() throws IllegalArgumentException when frontmatter is missing") {
        shouldThrow<IllegalArgumentException> {
            parseAgentDefinition("Just a body, no frontmatter.", source)
        }
    }

    test("parseAgentDefinition() throws IllegalArgumentException when closing delimiter is missing") {
        val md = """
            ---
            name: broken

            No closing delimiter.
        """.trimIndent()

        shouldThrow<IllegalArgumentException> {
            parseAgentDefinition(md, source)
        }
    }

    test("parseAgentDefinition() throws IllegalArgumentException when the system prompt body is empty") {
        val md = """
            ---
            name: empty
            description: No body.
            ---
        """.trimIndent()

        shouldThrow<IllegalArgumentException> {
            parseAgentDefinition(md, source)
        }
    }
})
