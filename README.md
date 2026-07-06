<div align="center">

# ✨ SoPhi

### The Kotlin-native agent harness for the JVM

**Bring your own LLM. Ship an agent in three lines, not three weeks.**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0-6DB33F)](https://spring.io/projects/spring-ai)

[Architecture deep-dive →](doc/Architecture.md)

</div>

---

SoPhi is a set of small, composable Maven modules built around one
battle-tested core: an agent loop with tool calling, branchable session
persistence, and automatic context compaction. Wrap it in a **terminal
app**, a **REST/SSE server**, or **embed it directly** in your own JVM
app — same core, three ways to run it.

## 🧩 Modules

| Module | What it gives you |
|--------|--------------------|
| ⚡ `sophi-ai` | Provider abstraction (`LLMProvider`) + Spring AI-backed `ClaudeProvider` / `OpenAICompatProvider` |
| 🧠 `sophi-core` | The agent loop, session tree (JSONL, branch/checkout), tool dispatch, context compaction |
| 📚 `sophi-skills` | Load capability packages from Markdown files with YAML frontmatter |
| 🔌 `sophi-extensions` | `SophiPlugin` / `AgentHook` — lifecycle hooks (`BEFORE_TURN`, `AFTER_TOOL`, `ON_ERROR`, ...) |
| 💻 `sophi-cli` | `sophi` terminal app — interactive TUI with slash commands |
| 🌐 `sophi-web` | Spring Boot REST + SSE server exposing sessions and turns over HTTP |
| 🛠️ `sophi-sdk` | `Sophi.runtime { }` DSL for embedding the agent in another JVM app |
| 🏗️ `sophi-infra` | Ready-made plugins and trackers: `BudgetTracker`, `PermissionGatePlugin`, `MetricsPlugin` |

They all sit on the same core, so switching between them later is a
one-line change, not a rewrite.

## 🚀 Build

```bash
mvn -q -DskipTests package
```

Requires an Anthropic API key for the Claude provider:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

---

## 💻 Use case 1: Interactive terminal agent (`sophi-cli`)

Run a chat session in your terminal, backed by Claude, with persistent
sessions you can branch and resume.

```bash
mvn -pl sophi-cli -am package
java -jar sophi-cli/target/sophi-cli-1.0.0-SNAPSHOT.jar --model claude-sonnet-4-5
```

Options:

```bash
sophi --session <id>          # resume an existing session
sophi --model <name>          # LLM model (default: claude-3-5-sonnet-20241022)
sophi --sessions-dir <path>   # where session JSONL files live (default: ~/.sophi/sessions)
sophi --agents-dir <path>     # directory of subagent definitions (default: ~/.sophi/agents)
sophi --system "<prompt>"     # system prompt for every turn
sophi --provider <name>       # 'claude' (default) or 'openai-compat' (Ollama, vLLM, ...)
sophi --base-url <url>        # required for --provider openai-compat
sophi --api-key <key>         # optional; falls back to ANTHROPIC_API_KEY for claude
```

Examples with local models:

```bash
# Ollama
sophi --provider openai-compat --base-url http://localhost:11434/v1 --model qwen2.5:7b

# vLLM
sophi --provider openai-compat --base-url http://localhost:8000/v1 --model Qwen/Qwen2.5-7B-Instruct
```

In-session slash commands:

```
/list       list saved sessions
/branch     print the active branch (entry ids + roles)
/checkout <entry-id>   jump to a different point in the session tree
/compact    summarize older turns to shrink context
exit / quit  end the session
```

The terminal redraws in place while a turn streams, instead of scrolling
line by line:

- **Live output** — streamed tokens and tool-call status update a single in-place region.
- **ESC to interrupt** — cancel a turn mid-stream; the partial reply is kept, tagged `[interrupted]`, and the prompt returns immediately.
- **Multi-line input** — end a line with `\` to continue on the next line, same convention as bash.
- **Non-interactive fallback** — piped stdin or non-TTY output drops to plain line-mode automatically, so scripted usage still works.

**Subagents:** `--agents-dir` points at a directory of Markdown files, each
describing a subagent the CLI can delegate a task to mid-session:

```markdown
---
name: researcher
description: "Looks things up without touching any files"
allowedTools: [read_file, web_search]
---

You are a research assistant. Investigate the task and report findings
concisely. You cannot modify any files.
```

The frontmatter's `name` and `description` are what the main agent sees when
choosing which subagent to delegate to; the Markdown body becomes that
subagent's system prompt. Delegation nests up to 3 levels deep by default.

**Good for:** local dev-tool style usage, exploring the agent loop, quick
one-off tasks — same category as a REPL.

---

## 🌐 Use case 2: Agent-over-HTTP (`sophi-web`)

Run Sophi as a Spring Boot service so a frontend, another service, or a
mobile app can talk to it over REST/SSE.

```bash
mvn -pl sophi-web -am spring-boot:run
```

Endpoints (`AgentController`, base path `/api`):

```
POST /api/sessions?title=...          create a session -> {id, entryCount, lastModified}
GET  /api/sessions                    list sessions
POST /api/sessions/{id}/turn          { "input": "..." } -> { sessionId, reply }
GET  /api/sessions/{id}/stream?input=...   Server-Sent Events, one event per token
```

Example:

```bash
curl -X POST localhost:8080/api/sessions
curl -X POST localhost:8080/api/sessions/<id>/turn \
  -H 'Content-Type: application/json' -d '{"input": "hello"}'
curl -N "localhost:8080/api/sessions/<id>/stream?input=hello"   # SSE stream
```

**Good for:** powering a chat UI, a Slack/Discord bot, or any client that
needs the agent behind a network boundary rather than in-process.

---

## 🛠️ Use case 3: Embedding Sophi in your own JVM app (`sophi-sdk`)

Use the `RuntimeBuilder` DSL to wire up a runtime with your own tools and
plugins, no CLI or HTTP layer required.

```kotlin
import dev.sophi.sdk.Sophi
import dev.sophi.ai.providers.ClaudeProvider
import dev.sophi.infra.PermissionGatePlugin
import dev.sophi.infra.MetricsPlugin

val runtime = Sophi.runtime {
    provider = ClaudeProvider(chatModel)     // any LLMProvider
    model = "claude-sonnet-4-5"
    systemPrompt = "You are a build assistant."

    tool(MyCustomTool())                     // implement dev.sophi.core.tools.Tool
    plugin(PermissionGatePlugin(allowedTools = setOf("read_file")))
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

---

## 🧰 Cross-cutting: skills and plugins

**Skills** (`sophi-skills`) are plain Markdown files with YAML frontmatter —
drop them in a directory and load them with `SkillLoader().load(dir)`:

```markdown
---
title: "Deploy runbook"
description: "How to deploy this service safely"
tags: [ops, deploy]
---

Steps to deploy...
```

**Plugins** (`sophi-extensions`) hook into the turn lifecycle
(`BEFORE_TURN` / `AFTER_TURN` / `BEFORE_TOOL` / `AFTER_TOOL` / `ON_ERROR`).
`sophi-infra` ships three ready to use:

- `PermissionGatePlugin(allowedTools)` — blocks any tool call not in an allowlist
- `MetricsPlugin(meterRegistry)` — emits Micrometer counters for turns started/completed/errored
- `BudgetTracker` — a standalone tracker (not a `SophiPlugin`) that throws `BudgetExceededException` once a token budget is exceeded; call `record()`/`used()` directly rather than registering it

Register plugins either manually (`RuntimeBuilder.plugin(...)` /
`PluginRegistry.register(...)`) or via JVM `ServiceLoader` discovery
(`PluginRegistry().discover()`).

---

## 🎯 Choosing a use case

| You want to... | Reach for |
|---|---|
| Poke at the agent yourself, or script one-off tasks | **`sophi-cli`** |
| Let a frontend, bot, or service talk to the agent | **`sophi-web`** |
| Add agent capability inside an existing JVM app | **`sophi-sdk`** |

All three share the same `sophi-core` agent loop and session format, so
switching between them later doesn't change how sessions or tools work —
start small, grow into the rest.
