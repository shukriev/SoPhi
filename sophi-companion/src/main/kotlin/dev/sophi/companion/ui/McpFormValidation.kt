package dev.sophi.companion.ui

import dev.sophi.mcp.config.McpTransport

/**
 * Validation for the add/edit MCP server form. [editingName] is the name of the server
 * currently being edited (null when adding) — excluded from the duplicate-name check so
 * saving with the field unchanged doesn't flag a collision with itself.
 */
fun mcpFormError(
    name: String,
    transport: McpTransport,
    commandText: String,
    url: String,
    existingNames: Set<String>,
    editingName: String?
): String? {
    val trimmedName = name.trim()
    val nameCollides = trimmedName.isNotBlank() && trimmedName != editingName && trimmedName in existingNames
    return when {
        trimmedName.isBlank() -> "Name is required"
        nameCollides -> "A server named \"$trimmedName\" already exists"
        transport == McpTransport.STDIO && commandText.isBlank() -> "Command is required for stdio"
        transport == McpTransport.HTTP && url.isBlank() -> "URL is required for http"
        else -> null
    }
}
