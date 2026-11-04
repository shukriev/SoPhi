package dev.sophi.core.agent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Set by [AgentLoop] around each round's tool calls: runs one more call the way the model's own calls run (before-tool
 * hook, permission rules, plan-first, confirmation, audit, after-tool hook, events, undo records) and returns its result
 * text. A tool that drives other tools (code_mode) calls them only through this, so it can never get around a check.
 */
class ToolCallScope(val call: suspend (name: String, argumentsJson: String) -> String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolCallScope>
}
