# Sophi Architecture

## Status

| Field | Value |
|-------|-------|
| Current milestone | M4 — sophi-web + sophi-sdk + sophi-infra |
| Modules complete | sophi-ai, sophi-core (session), sophi-core (loop + tools), sophi-cli (print mode), sophi-cli (full TUI), sophi-skills, sophi-extensions, sophi-web, sophi-sdk, sophi-infra |
| Last updated | 2026-07-01 |

---

## Vision and Non-Goals

Sophi is a Kotlin-native agent harness: the structural equivalent of Pi (earendil-works/pi) but built entirely on the JVM. It targets developers who live in the Kotlin/Spring ecosystem and want a harness they can understand, extend, and embed without touching TypeScript or Node.js.

**Core principles:**
- The harness adapts to your workflow, not the other way around
- Keep the core minimal; expose extension points for everything else
- A minimal system prompt — trust the model's training, don't over-specify
- Every feature that isn't core can be added via plugins

**Non-goals:**
- Sub-agents, plan mode, and MCP are not built in — plugin territory only
- Sophi does not replace Spring AI — it wraps it for provider abstraction only
- No Koog dependency — agent loop is custom (see ADR-001)

---

## Module Map

```
┌──────────────────────────────────────────────────────────────┐
│                      Interfaces                              │
│  sophi-cli   (Clikt + Mordant — terminal TUI)                │
│  sophi-web   (Spring Boot WebSocket + SSE)                   │
│  sophi-sdk   (embeddable library for Spring services)        │
└──────────────────────┬───────────────────────────────────────┘
                       │ AgentSession API
┌──────────────────────▼───────────────────────────────────────┐
│             sophi-core  (agent loop, written from scratch)   │
│  session/  context/  tools/  agent/  prompt/                 │
└──────────────────────┬───────────────────────────────────────┘
          ┌────────────┼─────────────┐
          │            │             │
┌─────────▼──────┐  ┌──▼────────┐  ┌▼───────────────────┐
│  sophi-ai      │  │sophi-     │  │sophi-extensions    │
│(Spring AI BOM  │  │skills     │  │(plugin SPI, hooks) │
│ thin wrapper)  │  │(Markdown) │  │                    │
└────────────────┘  └───────────┘  └────────────────────┘
┌──────────────────────────────────────────────────────────────┐
│                       sophi-infra                            │
│  Auth  ·  Budget tracker  ·  Observability  ·  Permissions   │
└──────────────────────────────────────────────────────────────┘
```

| Module | Purpose | Status |
|--------|---------|--------|
| `sophi-ai` | Spring AI thin wrapper — provider abstraction only | skeleton |
| `sophi-core` | Agent loop, session (JSONL tree), tools, compaction | skeleton |
| `sophi-skills` | Lazy-loaded Markdown skill packages | skeleton |
| `sophi-extensions` | Plugin SPI via JVM ServiceLoader, lifecycle hooks | skeleton |
| `sophi-mcp` | MCP client (stdio + Streamable HTTP) and server (stdio, via sophi-cli's `mcp-serve`); adapts tools into/out of dev.sophi.core.tools.Tool | skeleton |
| `sophi-cli` | Terminal CLI, TUI, slash commands, RPC mode | skeleton |
| `sophi-web` | Web UI, WebSocket, SSE, REST endpoints | skeleton |
| `sophi-sdk` | Embeddable library for Spring `@Service` beans | skeleton |
| `sophi-infra` | Auth, budget, observability, permission gates | skeleton |

**Dependency direction rules (never violate):**
- `sophi-core` never imports from `sophi-web`, `sophi-cli`, `sophi-sdk`, or `sophi-infra`
- `sophi-ai` never imports from `sophi-core`
- `sophi-skills` has no dependency on `sophi-core`

---

## Data Flow

```
User input
    │
    ▼
AgentLoop.turn(session, userInput, config)
    │
    ├─ PromptBuilder.build(session.branch())
    │       Converts SessionEntry list → List<Message> for CompletionRequest
    │
    ├─ loop (up to config.maxToolRounds):
    │       provider.complete(CompletionRequest)
    │           ├─ LLMResponse.Text   → append USER+ASSISTANT to session
    │           │                        sessionManager.save()
    │           │                        ContextCompactor.compact() if needed
    │           │                        return session
    │           ├─ LLMResponse.ToolUse → coroutineScope { async { tool.execute() }.awaitAll() }
    │           │                        append ASSISTANT(toolCalls) + TOOL messages to
    │           │                        in-memory list; loop
    │           └─ LLMResponse.Error  → throw IllegalStateException
    │
    └─ throw IllegalStateException("Max tool rounds exceeded")
```

Session entries persisted: only USER + ASSISTANT (text). Tool call/result exchanges are ephemeral within a turn.

---

## Key Interfaces

### LLMProvider (`dev.sophi.ai.api`)

The single boundary between `sophi-core` and the provider layer. `sophi-core` holds a
`ProviderRegistry`, selects a provider by name, and calls `complete()`. Zero Spring AI imports
anywhere in `sophi-core`.

```kotlin
interface LLMProvider {
    val name: String
    suspend fun complete(request: CompletionRequest): LLMResponse
    fun stream(request: CompletionRequest): Flow<String>
}
```

### CompletionRequest (`dev.sophi.ai.api`)

Everything the provider needs to call the model. `tools` is empty unless the agent loop
attaches tool definitions for the current turn.

```kotlin
data class CompletionRequest(
    val messages: List<Message>,
    val model: String,
    val maxTokens: Int = 4096,
    val temperature: Double = 0.7,
    val systemPrompt: String? = null,
    val tools: List<ToolDefinition> = emptyList()
)
```

### LLMResponse (`dev.sophi.ai.api`)

Sealed class. The agent loop pattern-matches on this in every turn.

```kotlin
sealed class LLMResponse {
    data class Text(val content: String, val usage: TokenUsage, val stopReason: String? = null) : LLMResponse()
    data class ToolUse(val calls: List<ToolCall>, val usage: TokenUsage) : LLMResponse()
    data class Error(val message: String, val cause: Throwable? = null) : LLMResponse()
}
```

### AgentSession (`dev.sophi.core.session`)

The in-memory model of a conversation. Entries form a tree via `parentId` links;
`branch()` returns the linear chain from root to the current tip; `checkout()` switches
the active tip to enable branching.

```kotlin
class AgentSession(
    val id: String,
    val title: String? = null,
    initialEntries: List<SessionEntry> = emptyList(),
    initialTipId: String? = null
) {
    val tip: SessionEntry?
    val entries: List<SessionEntry>
    fun append(role: EntryRole, content: String, metadata: Map<String, String> = emptyMap()): SessionEntry
    fun branch(): List<SessionEntry>
    fun checkout(entryId: String)
}
```

### SessionManager (`dev.sophi.core.session`)

Creates, persists, and loads sessions. `FileSessionManager` is the only implementation
in M1; the interface exists so `sophi-core` can be tested without touching the filesystem.

```kotlin
interface SessionManager {
    fun create(title: String? = null): AgentSession
    fun save(session: AgentSession)
    fun load(sessionId: String): AgentSession
    fun list(): List<SessionMeta>
}
```

### Tool (`dev.sophi.core.tools`)

The unit of capability the agent loop can invoke. Parameters and results are plain JSON strings;
the loop handles error catching and forwards errors back to the LLM as `"Error: <message>"`.

```kotlin
interface Tool {
    val name: String
    val description: String
    val parametersJson: String    // JSON Schema forwarded verbatim as ToolDefinition
    suspend fun execute(argumentsJson: String): String
}
```

### SophiPlugin + AgentHook (`dev.sophi.extensions`)

Lifecycle hooks are the extension point for observability, logging, and side effects without
modifying `AgentLoop`. A plugin declares which `HookPoint`s it cares about; `PluginRegistry`
dispatches to them in registration order.

```kotlin
enum class HookPoint { BEFORE_TURN, AFTER_TURN, BEFORE_TOOL, AFTER_TOOL, ON_ERROR }

data class HookContext(
    val sessionId: String,
    val userInput: String? = null,
    val toolName: String? = null,
    val error: Throwable? = null
)

interface AgentHook {
    val point: HookPoint
    suspend fun invoke(context: HookContext)
}

interface SophiPlugin {
    val name: String
    val version: String get() = "1.0.0"
    fun hooks(): List<AgentHook>
}
```

Plugins are discovered via JVM `ServiceLoader` (`PluginRegistry.discover()`) or registered
programmatically (`PluginRegistry.register(plugin)`).

### Skill + SkillLoader (`dev.sophi.skills`)

Skills are Markdown files with YAML frontmatter. `SkillLoader` reads a directory and returns
typed `Skill` objects; the module has no dependency on `sophi-core`.

```kotlin
@Serializable
data class SkillMetadata(
    val title: String,
    val description: String = "",
    val version: String = "1.0.0",
    val tags: List<String> = emptyList()
)

data class Skill(val metadata: SkillMetadata, val body: String, val source: Path)

class SkillLoader {
    fun load(directory: Path): List<Skill>
    fun loadFile(file: Path): Skill
}
```

---

## Architecture Decision Records

| ADR | Decision | Ruling |
|-----|----------|--------|
| [ADR-001](adr/ADR-001-custom-core-vs-koog.md) | Custom agent loop vs Koog | Custom `sophi-core` — Koog rejected |
| [ADR-002](adr/ADR-002-spring-ai-for-providers.md) | LLM provider layer | Spring AI starters — no DIY HTTP clients |
| [ADR-003](adr/ADR-003-maven-multi-module.md) | Build system | Maven multi-module — Gradle rejected |
| [ADR-004](adr/ADR-004-llm-contract-placement.md) | LLM contract placement | Contract in `sophi-ai`, no `sophi-contracts` module |
| [ADR-005](adr/ADR-005-jsonl-tree-sessions.md) | Session storage format | Append-only JSONL with parentId tree |
| [ADR-006](adr/ADR-006-tool-interface.md) | Tool interface design | suspend execute returning String |
| [ADR-007](adr/ADR-007-subagent-delegation.md) | Subagent delegation | Tool-based nested AgentLoop — no core-loop changes |

---

## Build Status

| Module | Milestone | Status | Article |
|--------|-----------|--------|---------|
| project skeleton | M0 | complete | — |
| `sophi-ai` | M1 | complete | [article-04](articles/article-04.md) |
| `sophi-core` (session) | M1 | complete | [article-05](articles/article-05.md) |
| `sophi-core` (loop + tools) | M1 | complete | [article-06](articles/article-06.md) |
| `sophi-cli` print mode | M1 | complete | [article-09](articles/article-09.md) |
| `sophi-skills` | M3 | complete | [article-07](articles/article-07.md) |
| `sophi-extensions` | M3 | complete | [article-08](articles/article-08.md) |
| `sophi-cli` full TUI | M2 | complete | [article-09](articles/article-09.md) |
| `sophi-web` | M4 | complete | [article-10](articles/article-10.md) |
| `sophi-sdk` + `sophi-infra` | M4 | complete | [article-11](articles/article-11.md) |
| `sophi-core` subagent delegation | M5 | complete | [article-13](articles/article-13.md) |
| `sophi-mcp` (client + server) | post-M5 | complete | — |
