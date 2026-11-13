# Embedding SoPhi in your JVM app (sophi-sdk)

[← Back to the README](../README.md)


Use the `RuntimeBuilder` DSL to wire up a runtime with your own tools and
plugins, no CLI or HTTP layer required.

```kotlin
import dev.sophi.sdk.Sophi
import dev.sophi.ai.providers.ClaudeProvider
import dev.sophi.infra.MetricsPlugin

val runtime = Sophi.runtime {
    provider = ClaudeProvider(chatModel)     // any LLMProvider
    model = "claude-sonnet-4-5"
    systemPrompt = "You are a build assistant."

    tool(MyCustomTool())                     // implement dev.sophi.core.tools.Tool
    grants(setOf("read_file"))               // tool names this runtime may use without confirmation
    plugin(MetricsPlugin(meterRegistry))
}

val sessionId = runtime.newSession(title = "release-check")
val reply = runtime.turn(sessionId, "What changed since the last tag?")
```

`provider` accepts any `LLMProvider` — for a local model instead of Claude, use
`dev.sophi.ai.providers.buildOpenAiCompatProvider` (from `sophi-ai`):

```kotlin
import dev.sophi.ai.providers.buildOpenAiCompatProvider

val runtime = Sophi.runtime {
    provider = buildOpenAiCompatProvider(
        baseUrl = "http://localhost:11434/v1", // Ollama; vLLM is typically :8000/v1
        apiKey = null,                          // no-auth mode for local servers
        model = "qwen2.5:7b"
    )
    systemPrompt = "You are a build assistant."
}
```

A custom tool is just:

```kotlin
class MyCustomTool : dev.sophi.core.tools.Tool {
    override val name = "my_tool"
    override val description = "Does something useful"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override suspend fun execute(argumentsJson: String): String = "result"
}
```

**Good for:** bolting an agent onto an existing JVM app (CI tool, internal
dashboard, batch job) without standing up a separate service.

