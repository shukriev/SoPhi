package dev.sophi.learning.export

import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import kotlinx.serialization.json.*

object ChatMessages {
    fun fromEntries(entries: List<SessionEntry>, systemPrompt: String?): JsonArray = buildJsonArray {
        systemPrompt?.let { add(buildJsonObject { put("role", "system"); put("content", it) }) }
        entries.forEach { e ->
            when {
                e.metadata.containsKey("toolCalls") -> add(buildJsonObject {
                    put("role", "assistant"); put("content", "")
                    put("tool_calls", buildJsonArray {
                        Json.parseToJsonElement(e.metadata.getValue("toolCalls")).jsonArray.forEach { call ->
                            val c = call.jsonObject
                            add(buildJsonObject {
                                put("id", c.getValue("id").jsonPrimitive.content)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", c.getValue("name").jsonPrimitive.content)
                                    put("arguments", c.getValue("argumentsJson").jsonPrimitive.content)
                                })
                            })
                        }
                    })
                })
                e.role == EntryRole.TOOL_RESULT -> add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", e.metadata["toolCallId"] ?: "")
                    put("content", e.content)
                })
                e.role == EntryRole.USER -> add(buildJsonObject { put("role", "user"); put("content", e.content) })
                e.role == EntryRole.ASSISTANT -> add(buildJsonObject { put("role", "assistant"); put("content", e.content) })
                else -> Unit   // SYSTEM entries are represented via systemPrompt
            }
        }
    }
}
