package dev.sophi.core.session

import kotlinx.serialization.Serializable

@Serializable
enum class EntryRole { SYSTEM, USER, ASSISTANT, TOOL_RESULT }
