# Sophi Architecture

## Status

| Field | Value |
|-------|-------|
| Current milestone | M6 — learning system (phases 1–4) complete |
| Modules complete | sophi-ai, sophi-core (session, loop + tools, subagents), sophi-cli (print mode, full TUI), sophi-skills, sophi-extensions, sophi-mcp, sophi-learning, sophi-web, sophi-sdk, sophi-infra |
| Last updated | 2026-07-12 |

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
┌──────────────────────────────────────────────────────────────┐
│              sophi-learning  (observes via hooks)            │
│  tool stats · lesson distillation · feedback · export        │
└──────────────────────────────────────────────────────────────┘
```

| Module | Purpose | Status |
|--------|---------|--------|
| `sophi-ai` | Spring AI thin wrapper — provider abstraction only | complete |
| `sophi-core` | Agent loop, session (JSONL tree), tools, compaction, subagents | complete |
| `sophi-skills` | Lazy-loaded Markdown skill packages | complete |
| `sophi-extensions` | Plugin SPI via JVM ServiceLoader, lifecycle hooks | complete |
| `sophi-mcp` | MCP client (stdio + Streamable HTTP) and server (stdio, via sophi-cli's `mcp-serve`); adapts tools into/out of dev.sophi.core.tools.Tool | complete |
| `sophi-learning` | Self-learning: tool reliability, session-end lesson distillation, preference feedback, SFT/DPO dataset export — observes via hooks, never blocks a turn | complete |
| `sophi-cli` | Terminal CLI, TUI, slash commands, RPC mode | complete |
| `sophi-web` | Web UI, WebSocket, SSE, REST endpoints | complete |
| `sophi-sdk` | Embeddable library for Spring `@Service` beans | complete |
| `sophi-infra` | Auth, budget, observability, permission gates | complete |

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

Session entries persisted: USER + ASSISTANT (text), plus — since ADR-009 — the tool rounds themselves: one ASSISTANT entry per round carrying serialized calls in `metadata["toolCalls"]` and one TOOL_RESULT entry per result, all tagged `metadata["replay"]="false"`. `PromptBuilder` and `ContextCompactor` filter `replay=false` entries out, so prompts are byte-identical to the pre-ADR-009 behavior; the entries exist for the learning system (evaluation trajectories, dataset export), never for re-prompting.

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
    val error: Throwable? = null,
    val argumentsJson: String? = null,   // BEFORE_TOOL
    val toolResult: String? = null,      // AFTER_TOOL
    val success: Boolean? = null,        // AFTER_TOOL
    val durationMillis: Long? = null     // AFTER_TOOL
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

`BEFORE_TOOL`/`AFTER_TOOL` are fired by bridging the agent loop's live `TurnEvent` stream:
each surface passes `pluginRegistry.turnEventBridge(sessionId)` as the loop's event callback,
so the loop itself never knows plugins exist. `AFTER_TURN`/`ON_ERROR` are dispatched by the
surface (CLI/web/SDK) when a turn settles — always best-effort (`runCatching`), never on the
user's critical path.

### LearningPlugin + LessonRecall (`dev.sophi.learning`)

The learning system is a `SophiPlugin` plus JSONL fold-stores under `~/.sophi/learning/`
(append-only, last record per id wins, deletion = tombstone). It observes tool events and
session outcomes, runs one LLM self-evaluation at session end (judgment + deduplicated
lessons + implicit feedback, strict JSON with one repair retry, silent no-op on failure),
and injects what it learned back via a single entry point:

```kotlin
class LearningPlugin(
    config: LearningConfig,
    model: String? = null,
    provider: LLMProvider? = null,          // enables the session-end evaluator
    sessionManager: SessionManager? = null
) : SophiPlugin {
    val toolStats: ToolStatsStore
    val lessonStore: LessonStore
    val preferenceStore: PreferenceStore
    suspend fun recordSessionEnd(sessionId: String)
    fun recordExplicitFeedback(sessionId: String, entryIndex: Int, polarity: String, reason: String?)
    fun promptSections(scope: String): String?   // reliability warnings + recalled lessons
}

interface LessonRecall {                          // ranking is swappable; `query` is the
    fun recall(scope: String, budgetTokens: Int,  // hook for future embedding-based recall
               query: String? = null): List<Lesson>
}
```

Offline, `sophi export` (package `dev.sophi.learning.export`, zero provider imports per
ADR-012) turns the captured history into `sft.jsonl` / `dpo.jsonl` / `manifest.json` —
redacted by default, deduplicated, deterministically split by session-id hash.

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
| [ADR-008](adr/ADR-008-learning-module-observing-via-hooks.md) | Learning system placement | Separate module observing via hooks — no core-loop changes |
| [ADR-009](adr/ADR-009-persist-tool-rounds-no-replay.md) | Trajectory persistence | Tool rounds as `replay=false` session entries — prompts byte-identical |
| [ADR-010](adr/ADR-010-session-end-self-evaluation-no-vector-store.md) | Lesson distillation | One session-end LLM self-eval; prompt-based dedup — vector store rejected |
| [ADR-011](adr/ADR-011-preference-capture-through-lesson-pipeline.md) | Preference feedback | Explicit + weighted implicit capture; steering through the lesson pipeline |
| [ADR-012](adr/ADR-012-offline-trajectory-export.md) | Fine-tuning support | Offline `sophi export` to SFT/DPO datasets — training out of scope |

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
| `sophi-learning` phase 1 — outcome capture | M6 | complete | [article-14](articles/article-14.md) |
| `sophi-learning` phase 2 — lesson distillation | M6 | complete | [article-15](articles/article-15.md) |
| `sophi-learning` phase 3 — preference feedback | M6 | complete | [article-16](articles/article-16.md) |
| `sophi-learning` phase 4 — trajectory export | M6 | complete | [article-17](articles/article-17.md) |
