package dev.sophi.companion.ui

import dev.sophi.mcp.config.McpTransport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class McpFormValidationTest : FunSpec({
    test("blank name is an error") {
        mcpFormError("", McpTransport.STDIO, "npx foo", "", emptySet(), null) shouldBe "Name is required"
        mcpFormError("   ", McpTransport.STDIO, "npx foo", "", emptySet(), null) shouldBe "Name is required"
    }

    test("a name matching an existing server (and not the one being edited) is an error") {
        mcpFormError("existing", McpTransport.STDIO, "npx foo", "", setOf("existing", "other"), null) shouldBe
            "A server named \"existing\" already exists"
    }

    test("editing a server and keeping its own name is not a collision") {
        mcpFormError("existing", McpTransport.STDIO, "npx foo", "", setOf("existing", "other"), editingName = "existing")
            .shouldBeNull()
    }

    test("renaming to a different, already-used name is still a collision") {
        mcpFormError("other", McpTransport.STDIO, "npx foo", "", setOf("existing", "other"), editingName = "existing") shouldBe
            "A server named \"other\" already exists"
    }

    test("stdio transport requires a non-blank command") {
        mcpFormError("s1", McpTransport.STDIO, "", "", emptySet(), null) shouldBe "Command is required for stdio"
        mcpFormError("s1", McpTransport.STDIO, "   ", "", emptySet(), null) shouldBe "Command is required for stdio"
    }

    test("http transport requires a non-blank url") {
        mcpFormError("s1", McpTransport.HTTP, "", "", emptySet(), null) shouldBe "URL is required for http"
        mcpFormError("s1", McpTransport.HTTP, "", "   ", emptySet(), null) shouldBe "URL is required for http"
    }

    test("a fully valid stdio entry has no error") {
        mcpFormError("s1", McpTransport.STDIO, "npx -y @mcp/fs /path", "", emptySet(), null).shouldBeNull()
    }

    test("a fully valid http entry has no error") {
        mcpFormError("s1", McpTransport.HTTP, "", "https://example.com/mcp", emptySet(), null).shouldBeNull()
    }

    test("leading/trailing whitespace on name is trimmed before the blank and collision checks") {
        mcpFormError("  existing  ", McpTransport.STDIO, "npx foo", "", setOf("existing"), null) shouldBe
            "A server named \"existing\" already exists"
    }
})
