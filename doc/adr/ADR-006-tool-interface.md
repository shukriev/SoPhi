# ADR-006: Tool interface design — suspend execute returning String

**Date:** 2026-06-28
**Status:** Accepted

## Context

The agent loop needs to dispatch tool calls received from the LLM. Two decisions:
1. Should `execute()` be a blocking or suspend function?
2. Should the return type be a raw String or a typed result?

## Decision

`Tool.execute(argumentsJson: String): suspend String`

Tool parameters and results are plain JSON strings. The interface is:

```kotlin
interface Tool {
    val name: String
    val description: String
    val parametersJson: String    // JSON Schema — sent to the LLM as tool definition
    suspend fun execute(argumentsJson: String): String
}
```

## Reasons

1. **Suspend execute.** Tools call external APIs, read files, or run subprocesses — all I/O-bound. A suspend signature lets the agent loop run multiple tool calls in parallel via `coroutineScope { async { ... }.awaitAll() }` without blocking threads.

2. **String parameters and results.** The LLM sends tool arguments as a JSON string; returning a JSON string lets tool results be forwarded to the LLM without re-serialisation. Typed parameters would require each tool to own a serialisation layer — unnecessary coupling for M1. Callers needing typed access parse the JSON themselves.

3. **No result wrapper.** Tools signal errors by throwing; the agent loop catches any exception and formats it as an error string before sending it back to the LLM. A `Result<String>` wrapper was considered but adds complexity with no M1 benefit.

## Consequences

- Tool authors must write `suspend fun execute()` even for synchronous tools (one-liner: `return "result"`)
- Error strings sent back to the LLM follow the pattern `"Error: <message>"` — no structured error type
- `parametersJson` is the tool's full JSON Schema as a string; the agent loop forwards it verbatim to `ToolDefinition.parametersJson`
