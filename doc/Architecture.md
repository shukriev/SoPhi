# Sophi Architecture

## Status

| Field | Value |
|-------|-------|
| Current milestone | M1 — sophi-core (session) |
| Modules complete | sophi-ai, sophi-core (session) |
| Last updated | 2026-06-27 |

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

_Added when sophi-core agent loop is implemented (Phase 5, Plan 3)._

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

_Remaining interfaces (`KharnessPlugin`, `AgentHook`) added as each module is implemented._

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

---

## Build Status

| Module | Milestone | Status | Article |
|--------|-----------|--------|---------|
| project skeleton | M0 | complete | — |
| `sophi-ai` | M1 | complete | [article-04](articles/article-04.md) |
| `sophi-core` (session) | M1 | complete | [article-05](articles/article-05.md) |
| `sophi-core` (loop + tools) | M1 | pending | [article-06](articles/article-06.md) |
| `sophi-cli` print mode | M1 | pending | [article-09](articles/article-09.md) |
| `sophi-skills` | M3 | pending | [article-07](articles/article-07.md) |
| `sophi-extensions` | M3 | pending | [article-08](articles/article-08.md) |
| `sophi-cli` full TUI | M2 | pending | [article-09](articles/article-09.md) |
| `sophi-web` | M5 | pending | [article-10](articles/article-10.md) |
| `sophi-sdk` + `sophi-infra` | M5 | pending | [article-11](articles/article-11.md) |
