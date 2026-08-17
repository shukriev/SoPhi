# Sophi Architecture

## Status

| Field | Value |
|-------|-------|
| Current milestone | M7 — Jane's Theory memory (palace v1) complete |
| Modules complete | sophi-ai, sophi-core (session, loop + tools, subagents), sophi-cli (print mode, full TUI), sophi-skills, sophi-extensions, sophi-mcp, sophi-hub, sophi-learning, sophi-web, sophi-sdk, sophi-companion, sophi-infra, sophi-memory, sophi-schedule |
| Modules in progress | sophi-calendar (native OS calendar integration — macOS only; Windows/Linux deferred) |
| Designs approved, not yet implemented | Tiered tool confirmation & grants (ADR-016) — `RiskLevel` gains `CAUTION`; `Tool.riskLevel` becomes argument-aware; `ConfirmationPolicy` batches per round; `AgentLoop.grants` replaces `AllowlistConfirmationPolicy`; `PermissionGatePlugin` retired |
| Last updated | 2026-08-10 |

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
│   sophi-companion  (Compose Multiplatform Desktop tray app)  │
│   Chat · Sessions · MCP · Goals — native OS notifications    │
│   embeds sophi-hub's HubServer — CLI session monitoring &    │
│   remote control (ADR-023)                                   │
│   (ADR-022 — standalone Gradle module, outside the reactor)  │
└──────────────────────┬───────────────────────────────────────┘
                       │ embeds sophi-sdk in-process (no HTTP hop)
┌──────────────────────▼───────────────────────────────────────┐
│                      Interfaces                              │
│  sophi-cli   (Clikt + Mordant — terminal TUI)                │
│  sophi-web   (Spring Boot WebSocket + SSE)                   │
│  sophi-sdk   (embeddable library for Spring services)        │
└──────────────────────┬───────────────────────────────────────┘
                       │ AgentSession API
┌──────────────────────▼───────────────────────────────────────┐
│             sophi-core  (agent loop, written from scratch)   │
│  session/  context/  tools/  agent/  agent/plan/  prompt/     │
│  agent/plan/: Plan · Planner · StepCritic · PlanRunner ·      │
│              DecomposeGoalTool · PlanCritic · TreePlanner      │
│  (ADR-018 — Plan-and-Execute, supersedes GoalRunner)          │
│  (TreePlanner — widened replan search, goal-mode, probation)  │
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
│  Auth  ·  Budget tracker  ·  Observability                   │
└──────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────┐
│              sophi-learning  (observes via hooks)            │
│  tool stats · lesson distillation · feedback · export        │
└──────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────┐
│        sophi-memory  (Jane's Theory — declarative memory)    │
│  MemoryTechnique SPI · JanesPalace (rooms, salience, decay,  │
│  narrative graph, profile) · recall via ContextContributor   │
└──────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────┐
│     sophi-schedule  (recurring & goal-based task scheduler)  │
│  ScheduleEngine (tickOnce/runNow) · TaskStore / RunLog ·      │
│  Notifier · manage_scheduled_task Tool · Goal mode now runs   │
│  via sophi-core's PlanRunner (ADR-018 — GoalRunner retired)   │
│  Goal-mode planner is a TreePlanner (k-way replan search,     │
│  probation, see Plan + PlanRunner section)                    │
└──────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────┐
│    sophi-calendar  (native OS calendar integration)          │
│  CalendarProvider · MacCalendarProvider (osascript) ·        │
│  create/list/get/update/delete_calendar_event, list_calendars │
└──────────────────────────────────────────────────────────────┘
```

| Module | Purpose | Status |
|--------|---------|--------|
| `sophi-ai` | Spring AI thin wrapper — provider abstraction only | complete |
| `sophi-core` | Agent loop, session (JSONL tree), tools, compaction, subagents, plan-and-execute (`agent/plan`), recursive goal decomposition (task trees) with a tree-wide execution budget | complete |
| `sophi-skills` | Lazy-loaded Markdown skill packages; `SkillRegistry` merges global + project directories | complete |
| `sophi-extensions` | Plugin SPI via JVM ServiceLoader, lifecycle hooks | complete |
| `sophi-mcp` | MCP client (stdio + Streamable HTTP) and server (stdio, via sophi-cli's `mcp-serve`); adapts tools into/out of dev.sophi.core.tools.Tool | complete |
| `sophi-hub` | Local-only (127.0.0.1) WebSocket hub — `HubEvent`/`HubCommand` protocol, `HubServer` (embedded by `sophi-companion`), `HubClient` (used by `sophi-cli`); lets the companion monitor and remote-control running CLI sessions (ADR-023) | complete |
| `sophi-learning` | Self-learning: tool reliability, session-end lesson distillation, preference feedback, SFT/DPO dataset export — observes via hooks, never blocks a turn | complete |
| `sophi-memory` | Declarative memory (Jane's Theory): MemoryTechnique SPI, JanesPalace rooms/salience/decay/profile, per-turn recall via ContextContributor, true deletion — best-effort, never breaks a turn | complete |
| `sophi-schedule` | Recurring & goal-based task scheduler: `ScheduleEngine` (concurrent `tickOnce`/`runNow`), `TaskStore`/`RunLog`, `Trigger` (interval/cron/once/manual, cron via `com.cronutils`), `Notifier` (macOS), `manage_scheduled_task` Tool — local-only, OS-scheduler-driven. Goal mode (LLM-judged/shell-checked stop conditions) runs via `sophi-core`'s `PlanRunner` (ADR-018) rather than its own `GoalRunner`, which is retired; the `Planner` it hands `PlanRunner` is a `TreePlanner` widening the replan search (probation, see Plan + PlanRunner) | complete |
| `sophi-calendar` | Native OS calendar CRUD: `CalendarProvider` seam, `MacCalendarProvider` (AppleScript/Calendar.app) — Windows/Linux deferred; six create/read/update/delete/list Tools | in progress |
| `sophi-cli` | Terminal CLI, TUI, slash commands, RPC mode | complete |
| `sophi-web` | Web UI, WebSocket, SSE, REST endpoints | complete |
| `sophi-sdk` | Embeddable library for Spring `@Service` beans | complete |
| `sophi-companion` | OS tray/menu-bar desktop app (Compose Multiplatform Desktop) embedding `sophi-sdk` in-process: Chat/Sessions/MCP/Goals tabs, per-session `StateFlow` with one coroutine per turn, in-process `ScheduleEngine` poll loop, cross-platform native notifications, `jpackage` bundles. Standalone Gradle project — **not** in the root Maven `<modules>`; consumes `dev.sophi:sophi-sdk` via `mavenLocal()` (ADR-022) | complete |
| `sophi-infra` | Auth, budget, observability | complete |

**Dependency direction rules (never violate):**
- `sophi-core` never imports from `sophi-web`, `sophi-cli`, `sophi-sdk`, or `sophi-infra`
- `sophi-ai` never imports from `sophi-core`
- `sophi-skills` has no dependency on `sophi-core`
- `sophi-core` importing `sophi-ai` is allowed (established by ADR-017's `RiskClassifier`,
  extended by ADR-018's `Planner`/`StepCritic`) — the one-directional rule above
  (`sophi-ai` never imports `sophi-core`) is what actually holds, not "no dependency at all"
- `sophi-core` never imports from `sophi-extensions` or `sophi-learning` — both depend on
  `sophi-core`, not the reverse. Features in `sophi-core` that need their SPIs (memory
  context, lesson feedback) take an injected callback instead (see `Planner`/`PlanRunner`
  below)

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

### Memory turn lifecycle (when `--memory` is enabled)

`sophi-memory`'s `MemoryPlugin` is the harness's first `ContextContributor` (ADR-013):

```
User input
    │
    ├─ PluginRegistry.collectContext(sessionId, input)      ← BEFORE the turn, ≤2s budget
    │       MemoryPlugin.contribute → JanesPalace.recall:
    │       embed query → route the top 3 rooms (descriptor cosine; configurable routeTopK) →
    │       score β₁·semantic + β₂·decayed-priority + β₃·profile-resonance →
    │       expand neighbors + causal threads → privacy guard →
    │       <memory_context> block appended to THIS turn's system prompt
    ▼
TurnController.runTurn(...)                                  ← unchanged loop
    │
    └─ AFTER_TURN hook (fire-and-forget coroutine):
            MemoryPlugin → JanesPalace.observe:
            one cheap LLM verdict (significance, room, emph/aff, links, corrections)
            + system-side novelty/repetition → α ≥ 0.35 → write memory + embedding + edges
```

Recall adds one embedding call to the turn path and no LLM call; encoding adds one async
LLM call after the turn. Every memory path is best-effort — a failure degrades to a
memory-less turn, never a broken one. Consolidation (merge/strengthen/compress/prune)
runs at session end when >24h since the last run, and via `sophi memory consolidate`.

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

### EmbeddingProvider (`dev.sophi.ai.api`)

The boundary `sophi-memory` calls through for text embeddings — a plain-HTTP OpenAI-compat
`/v1/embeddings` implementation ships in `sophi-ai`'s providers package, wired by `sophi-cli`
behind `--embedding-model`/`--embedding-base-url`/`--embedding-dimensions`.

```kotlin
interface EmbeddingProvider {
    val dimensions: Int
    suspend fun embed(texts: List<String>): List<FloatArray>
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
`riskLevel` is argument-aware (ADR-016) — most tools ignore the argument and return a
constant, but `bash`/`manage_scheduled_task`/`delegate_to_subagent` inspect it to classify
accurately rather than adopting one fixed worst-case tier. `invoke_claude_code`
(`RunClaudeCodeTool`, ADR-019) is the deliberate exception in the other direction: it always
returns `DESTRUCTIVE`/`HIGH_RISK`, never argument-dependent, because it spawns an entire
autonomous coding session — no argument value makes that safe enough to classify down.

```kotlin
enum class RiskLevel { SAFE, CAUTION, DESTRUCTIVE }

interface Tool {
    val name: String
    val description: String
    val parametersJson: String    // JSON Schema forwarded verbatim as ToolDefinition
    fun riskLevel(argumentsJson: String): RiskLevel = RiskLevel.SAFE
    fun ruleVerdict(argumentsJson: String): RuleVerdict = RuleVerdict.UNKNOWN
    suspend fun execute(argumentsJson: String): String
}
```

`ruleVerdict()` is consulted only for calls that already need a confirmation decision (i.e.
`riskLevel()` returned `CAUTION`/`DESTRUCTIVE`) — it's how auto mode (see below) decides which
of those calls are still low-risk enough to skip the prompt. Default `UNKNOWN` means "no
opinion," so a tool with no override degrades to the LLM classifier rather than silently
auto-approving.

### ConfirmationPolicy + grants (`dev.sophi.core.tools`)

Gates non-`SAFE` tool calls in `AgentLoop` (ADR-016). One batched request per
tool-calling round, not one per call — matching the round's already-parallel
execution. `AgentLoop.grants` is a `Set<String>` of tool names a scope may run without
asking again at all, regardless of tier; `ScheduledTask.toolGrants` and
`delegate_to_subagent`'s `expected_tools` are the two things that populate it.

```kotlin
data class ConfirmationRequest(
    val callId: String, val toolName: String, val argumentsJson: String, val riskLevel: RiskLevel
)

fun interface ConfirmationPolicy {
    suspend fun confirm(requests: List<ConfirmationRequest>): Map<String, Boolean>  // callId -> allowed

    companion object {
        val ALLOW_ALL: ConfirmationPolicy
        val DENY_ALL: ConfirmationPolicy   // anything not SAFE and not in grants is denied
    }
}
```

`CAUTION` auto-runs only when a human is attending the session (`TerminalConfirmationPolicy`
in `sophi-cli`); unattended contexts (`sophi-web`, `sophi-sdk`, ungranted `sophi-schedule`
runs) treat it the same as `DESTRUCTIVE` — a grant or an explicit confirmation, or it's
denied. `manage_scheduled_task`/`delegate_to_subagent` report `DESTRUCTIVE` themselves
whenever the call would populate a grant, so proposing future or delegated power goes
through this same mechanism instead of being a `SAFE` side door.

### Auto mode (`dev.sophi.core.tools`, ADR-017)

```kotlin
fun interface RiskClassifier {
    suspend fun classify(
        toolName: String, toolDescription: String, tier: RiskLevel, argumentsJson: String
    ): RuleVerdict
}
```

Layers on top of the mechanism above without changing `AgentLoop` — it's still just one more
`ConfirmationPolicy` implementation, swapped in for `TerminalConfirmationPolicy`:

- **`AutoModeConfirmationPolicy`** — for each request, checks the tool's `ruleVerdict()` first;
  `UNKNOWN` falls back to a `RiskClassifier` (`LlmRiskClassifier` in production, one non-streaming
  `LLMProvider.complete()` call, fails safe to `HIGH_RISK` on any error/timeout/malformed
  response). `LOW_RISK` auto-approves; everything else batches into one call to a wrapped
  fallback `ConfirmationPolicy` (e.g. `TerminalConfirmationPolicy`), preserving the existing
  grouped-prompt UX for whatever still needs a human.
- **`ToggleableConfirmationPolicy`** — a `@Volatile var autoModeEnabled: Boolean` wrapper that
  routes to either the auto or manual policy per call, letting the CLI's `--auto` flag and
  `/auto` command flip auto mode without rebuilding `AgentLoop`.

**God mode** (`--god-mode`, CLI-only, fixed for the session — no slash command) reuses this same
`AutoModeConfirmationPolicy` with a different classifier: `RiskClassifier.ALWAYS_LOW_RISK` answers
`LOW_RISK` unconditionally instead of calling an LLM, so every `UNKNOWN` rule verdict auto-approves
too — only an explicit `HIGH_RISK` rule verdict still prompts. `--god-mode` takes precedence over
`--auto` when both are passed.

### Plan + PlanRunner (`dev.sophi.core.agent.plan`, ADR-018)

Sits above `AgentLoop` exactly where `sophi-schedule`'s `GoalRunner` sits today — `PlanRunner`
calls `agentLoop.turn()` once per step, treating each step's instruction as that turn's
`userInput`. `AgentLoop` itself needs no changes.

```kotlin
data class Plan(
    val id: String, val goalPrompt: String, val steps: List<PlanStep>,
    val version: Int = 1, val parentPlanId: String? = null
)

data class PlanStep(
    val id: String, val instruction: String, val dependsOn: List<String> = emptyList(),
    val status: StepStatus = StepStatus.Pending,
    val confidence: Double? = null, val modelOverride: String? = null
)

enum class StepStatus { Pending, Running, Done, Failed }

interface Planner {
    suspend fun plan(goalPrompt: String, context: List<String> = emptyList()): Plan
    suspend fun replan(current: Plan, anchorStepId: String, reason: String, context: List<String> = emptyList()): Plan
}

fun interface StepCritic {   // shaped like RiskClassifier (ADR-017), fails OPEN not closed
    suspend fun judge(step: PlanStep, agentOutput: String): Double   // confidence [0.0, 1.0]
}
```

`version`/`parentPlanId` make replanning diff-based: a replan carries every already-`Done`
step over unchanged and only regenerates the failed/low-confidence tail, rather than mutating
in place or discarding the whole plan — plan history is auditable (v1 → v2 → v3) and no
completed work is redone. Independent steps (via `dependsOn`) run concurrently through the
same `async`/`awaitAll` shape `AgentLoop` already uses for tool-call rounds, gated by an
explicit `PlanRunnerConfig.allowParallelSteps` flag (default `false`) rather than introspecting
whether the session's `ConfirmationPolicy` is interactive — `sophi-schedule`'s always-unattended
`ScheduleEngine` sets it `true`.

`sophi-core` cannot depend on `sophi-extensions` or `sophi-learning` (both depend on it, not
the reverse), so memory-informed planning and the plan-to-lesson feedback loop are wired
through plain injected callbacks instead of direct SPI dependencies:
`Planner`'s `contextProvider: suspend (String) -> List<String>` and `PlanRunner`'s
`onPlanComplete: suspend (PlanOutcome) -> Unit`. See ADR-018.

### GoalController + the `/goal` command (`dev.sophi.cli.goal`, ADR-025)

ADR-025 folded its progress needs into ADR-020's existing `PlanProgressEvent` rather than
standing up a second stream: `PlanReady`, `StepAttempt` (which model, which child session,
attempt 1 or the escalation re-run) and `Escalating` joined `StepStarted`/`StepFinished`/
`Replanned`/`Decomposed`, and `Replanned` now carries the replacement `Plan` itself so a UI can
re-render the step list. Raw per-token `TurnEvent`s stay on `PlanRunner`'s separate `onEvent`
seam — the hub/companion bridge wants them unwrapped, and a renderer that wants both subscribes
to both. `PlanRunner` fires the boundary before the turn events of the step it opens, which is
what lets a renderer reset per-step state safely. `PlanRunner.run(...)` also gained
`initialPlan: Plan? = null` so a caller can generate a plan, show it, and only pass it to
`run()` after approval, without a new `PlanFinalStatus` value for "declined".

`sophi-cli`'s `dev.sophi.cli.goal` package is the first interactive consumer:
`GoalController.run(session, input, trigger: GoalTrigger)` — parses (or, for an autonomous
trigger, treats the message as literal task text), plans, previews, runs the per-invocation
`PlanRunner` under an ESC race, and settles — returning `GoalRunResult` (`Ran`/`Declined`).
`GoalRenderer` consumes both seams: steps still execute in `PlanRunner`'s isolated child
sessions, but the anchor session gets one `replay=false` entry per finished step plus one
replayed summary entry, so `/branch` and a follow-up turn both see the episode without paying
its full token cost. Because /goal drives `PlanRunner` directly instead of
`SophiRuntime.streamTurn`, it settles its own episode through `SophiRuntime.settleExternalTurn`
and plans against `SophiRuntime.contextFor`, so AFTER_TURN hooks and memory context behave
exactly as they do for a chat turn. See ADR-025 for the full rationale, including why the
plan-preview prompt uses `input.readLine()` rather than `input.awaitYesNo()`.

### TreePlanner + PlanCritic (`dev.sophi.core.agent.plan`, ADR-024 — probation)

`PlanRunner` already runs a depth-first search with backtracking: a failed step anchors a
replan, `Planner.replan()` regenerates one new tail, and the cycle repeats up to
`maxReplans`. That search is width 1 — one candidate continuation, executed without ever
being compared against an alternative. `TreePlanner` is a `Planner` decorator that widens
exactly that node:

```kotlin
fun interface PlanCritic {   // scores an unexecuted candidate — StepCritic scores a finished one
    suspend fun score(goalPrompt: String, candidate: Plan, failureReason: String): Double
}

class TreePlanner(
    private val delegates: List<Planner>,   // one LlmPlanner per temperature — see below
    private val critic: PlanCritic
) : Planner {
    // plan() delegates to delegates.first() untouched — no failure evidence yet to score against.
    // replan() with 1 delegate short-circuits to a plain call (byte-identical to pre-search).
    // With >1 delegates: runs every delegate's replan() concurrently, scores each candidate
    // with the critic, returns the highest score. maxBy returns the FIRST maximum, so an
    // all-fail-open critic (every score 1.0) reproduces the old width-1 result deterministically.
}
```

Losing candidates are data and never execute, so the search costs only extra planner/critic
LLM calls — no agent turns, no tool calls, no side effects, and no `RunBudget` consumption
(`RunBudget` counts `executeOnce` invocations only). `LlmPlanCritic` mirrors `LlmStepCritic`
and fails OPEN for the same reason (efficiency gate, not a safety gate) — a provider timeout
or unparseable response scores `1.0`, not a rejection.

`ScheduleEngine` wires this into goal-mode tasks only (`TaskMode.Goal`); the interactive CLI's
planner is untouched:

```kotlin
TreePlanner(
    delegates = planSearchTemperatures().map { LlmPlanner(provider, model, temperature = it) },
    critic = LlmPlanCritic(provider, model, timeout = 300.seconds)
)
```

**Kill switch.** `planSearchTemperatures()` reads `SOPHI_TOT_SEARCH_ENABLED` via
`System.getenv` (the `BRAVE_SEARCH_API_KEY` precedent in `SophiCli.kt`): `false` or `0`,
case-insensitive, collapses the ladder to `listOf(0.0)`, which `TreePlanner` short-circuits on
a single delegate — byte-identical pre-search behaviour at zero extra cost, no change needed
inside `TreePlanner`. Anything else, including unset, keeps the ladder. It fails safe *toward
ON* deliberately: a kill switch that treated a typo as "off" would produce a "no effect"
reading that looks like probation evidence. This is also how the A/B's baseline arm was run,
and it is what makes the feature revertible without a deploy while on probation.

`LlmPlanner`'s default `temperature = 0.0` would make every delegate return an identical
tail, so the temperature ladder is what actually buys candidate diversity — `TreePlanner`
itself has no diversity mechanism of its own. `LlmPlanCritic`'s 300s timeout (vs.
`LlmStepCritic`'s 30s default) exists because `PlanCritic` failing open is not a safe
degrade the way `StepCritic` failing open is: every candidate tying at `1.0` collapses the
search to width-1 while still *paying* for every extra planner/critic call — a silent,
expensive no-op rather than a safe fallback. A 9B local reasoning model measured 166s for a
single score call; hosted models (DeepSeek: ~3s) never approach the old 30s default.

**Why probation, not a graduated feature:** this shipped from a brainstorm → plan →
implementation cycle, so ADR-024 was written retroactively rather than gating the design (see
`docs/superpowers/plans/2026-08-14-tot-widened-replan.md` and the paired spec — both
gitignored, local-only). DeepSeek-backed probes and a 15-run A/B (also local-only, see
`TODO_TASK.md`) found the mechanism sound — the ladder produces genuinely distinct
candidates under real strategic ambiguity, and the critic discriminates well (spread up to
0.8, correctly scoring a bad plan near 0.0) — but did not demonstrate value: baseline
single-tail replan already scored 0.85–0.9 and found root cause reliably across tested
scenarios, with the search changing the selected candidate only 1 of 4 times, within the
critic's own noise. The end-to-end A/B itself was underpowered (both arms hit
`maxIterations` regardless of arm) and isn't real evidence either way.

Rather than delete outright or call it done, `RunOutcome`/`RunRecord` (`sophi-schedule`) now
records `replans: Int?` and `decompositions: Int?` per goal run — `null` for a `Recurring`
task (no plan ran at all), `0` for a goal run that genuinely never replanned, so the two
stay distinguishable in `sophi schedule log`. A failed step decomposes *before* it replans
(ADR-020's `canDecompose` check runs first), so `replans` alone can't tell "the search ran"
from "decomposition intercepted the failure" — both counts are surfaced together for that
reason. This is what makes "watch it on real workloads" an actual, decidable probation
review instead of a permanent unknown.

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

### ContextContributor + PluginRegistry.collectContext (`dev.sophi.extensions`)

The per-turn counterpart to the fire-and-forget hooks above (ADR-013): a plugin that also
implements `ContextContributor` can inject text into *this* turn's prompt, not just observe
after the fact. `collectContext` runs every contributor under its own timeout, swallows
failures and timeouts (contribution is always best-effort) but rethrows `CancellationException`
so it never absorbs the caller's own cancellation.

```kotlin
interface ContextContributor {
    suspend fun contribute(sessionId: String, userInput: String): String?
}

// PluginRegistry
suspend fun collectContext(
    sessionId: String,
    userInput: String,
    timeoutMillis: Long = 2_000
): List<String>
```

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

### MemoryTechnique (`dev.sophi.memory`)

Technique-agnostic declarative-memory SPI (ADR-013). `JanesPalace` is the first and only
implementation: five typed rooms, salience computed at encoding, decay-weighted retrieval,
causal narrative links, a confidence-weighted user profile, and true deletion. `MemoryPlugin`
adapts it to the harness — `contribute()` (`ContextContributor`) calls `recall`, the
`AFTER_TURN` hook fire-and-forgets `observe`. Storage is user-global under `~/.sophi/memory/`.

```kotlin
interface MemoryTechnique {
    suspend fun recall(query: RecallQuery): MemoryBlock?
    suspend fun observe(turn: TurnObservation)
    suspend fun consolidate(nowMs: Long): ConsolidationReport
    suspend fun forget(request: ForgetRequest): ForgetResult
    suspend fun search(query: String, k: Int): List<MemoryView>
    fun browse(filter: BrowseFilter): List<MemoryView>
    fun profileView(): List<ProfileAttributeView>
    fun updateProfile(action: ProfileAction): Boolean
    fun explainLastRecall(): String?
}
```

`ForgetEngine` (behind `JanesPalace.forget`) performs the compacting rewrite: re-links the
causal chain around the removed memory, reduces profile evidence, and deletes
`last-recall.txt` so a forgotten memory cannot resurface through the explain path.
`ForgetEngine.preview(id)` / `JanesPalace.previewForget(id)` compute the same impact
non-destructively, so `sophi memory forget` can show the blast radius before committing.
`ForgetEngine.purgeSoftDeleted(cutoffMs, nowMs)` is the consolidation-time sweep that
physically drops soft-deleted memories once they clear the retention cutoff.

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

`SkillRegistry` (also `dev.sophi.skills`, same no-`sophi-core`-dependency rule) merges a
global (`~/.sophi/skills`) and a project-local (`./.sophi/skills`) directory into one
id-keyed lookup — the id is the filename stem (`code-review.md` → `code-review`), and a
project-local skill overrides a global one of the same id. Missing directories and
individual malformed files are tolerated, not fatal.

```kotlin
class SkillRegistry(skills: Map<String, Skill>) {
    fun get(id: String): Skill?
    fun all(): List<Pair<String, Skill>>

    companion object {
        fun load(globalDir: Path, projectDir: Path, loader: SkillLoader = SkillLoader()): SkillRegistry
    }
}
```

`SkillInstaller` (also `dev.sophi.skills`, same no-`sophi-core`-dependency rule) installs
Claude Code-shaped skills — `<name>/SKILL.md` with `name:`/`description:` frontmatter,
optionally bundling extra files — from a local path or a shallow `git clone` of a repo
URL, normalizing each into the flat `<id>.md`/`title:`/`description:` shape
`SkillRegistry` reads. The id is the skill's containing folder name, not the frontmatter
`name:` field. Bundled sibling files are copied to a sibling `<id>/` folder (invisible to
`SkillRegistry`'s `*.md`-only glob) with a note line in the normalized body pointing at
its absolute path, so the LLM can still reach them via its own `read_file`/`bash` tools.
Already-installed ids are skipped, never overwritten.

```kotlin
data class InstallResult(val installed: List<String>, val skipped: List<String>, val notFound: List<String>)

class SkillInstaller {
    fun install(source: String, targetDir: Path, only: Set<String> = emptySet()): InstallResult
}
```

Reachable two ways: `sophi skill install <source> [--only a,b] [--project]` (`SkillInstallCommand`),
and the LLM-callable `install_skill` tool (`InstallSkillTool`, `DESTRUCTIVE`-tier — every
call writes files, gated by the normal confirmation-policy path like `write_file`).

`sophi-cli` is the module that actually consumes it — it sits at the intersection of
`sophi-core` (for the `Tool` interface) and `sophi-skills`, which neither module can see
on its own. `SkillTool` (`sophi-cli/.../SkillTool.kt`) wraps a `SkillRegistry` as a
`SAFE` `Tool` named `skill`: its `description` lists every loaded skill's id and
one-line description, and calling it with `{"name": "<id>"}` returns that skill's body
as the tool result, which becomes context for the LLM's next step. The `/skill` slash
command (`SlashHandler`, `SlashCommands.kt`) shares the same registry: `/skill` or
`/skill list` prints the available ids, `/skill <id>` appends the skill's body to the
session directly as a `TOOL_RESULT` entry — the same shape the tool call itself would
produce — letting a user force a specific skill into context without waiting on the LLM
to decide.

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
| [ADR-013](adr/ADR-013-memory-as-plugin-context-contributor.md) | Declarative memory placement | Separate sophi-memory module; per-turn context via new ContextContributor SPI |
| [ADR-014](adr/ADR-014-scheduled-goal-tasks.md) | Scheduled & goal-based tasks | Trigger×Mode unification; local-only for v1; per-task destructive allowlist; OS-scheduler-first tick model |
| [ADR-015](adr/ADR-015-native-os-calendar-integration.md) | Native OS calendar integration | `CalendarProvider` seam; per-action risk-gated tools; structured recurrence; macOS-only v1, Windows/Linux deferred |
| [ADR-016](adr/ADR-016-tiered-tool-confirmation.md) | Tiered tool-call confirmation and grants | Three-tier argument-aware `RiskLevel`; batched `ConfirmationPolicy`; `AgentLoop.grants` replaces `AllowlistConfirmationPolicy`; `PermissionGatePlugin` retired |
| [ADR-017](adr/ADR-017-auto-mode-hybrid-risk-classifier.md) | Auto mode | New ConfirmationPolicy layering rule+LLM classification on top of existing tiered confirmation; fail-safe on any classifier error; CLI-only, runtime-toggleable |
| [ADR-018](adr/ADR-018-plan-and-execute.md) | Plan-and-Execute upgrade | General `sophi-core` capability (`agent/plan`) replaces `sophi-schedule`'s `GoalRunner`; diff-based replanning; explicit `allowParallelSteps` flag instead of `ConfirmationPolicy` introspection; memory/learning integration via injected callbacks, not direct dependencies |
| [ADR-019](adr/ADR-019-invoke-claude-code-tool.md) | `invoke_claude_code` tool | One new Tool in `sophi-core/tools/`, no new module; `riskLevel`/`ruleVerdict` hardcoded `DESTRUCTIVE`/`HIGH_RISK`, never argument-dependent; two independent gates (outer `toolGrants`, inner `--permission-mode auto`); no orchestration code — `PlanRunner` does per-task decomposition |
| [ADR-020](adr/ADR-020-generalized-goal-decomposition.md) | Generalized goal decomposition | Recursion inside PlanRunner (no new module/type); two triggers over one decomposeStep seam; shared RunBudget; LlmJudged-only sub-plans; two entry points (decompose_goal tool + /plan) over one buildPlanRunner factory |
| [ADR-022](adr/ADR-022-sophi-companion.md) | `sophi-companion` OS tray app | Standalone Gradle module outside the Maven reactor (Compose Desktop is Gradle-only); embeds `sophi-sdk` in-process, HTTP via `sophi-web` rejected; five additive SDK/core/mcp gaps fixed rather than worked around (`ToolRegistry.unregister`, per-server MCP connect/disconnect, `McpConfigWriter` + `McpServerConfig.enabled`, `SessionManager.rename`/`delete` + title persistence, `SophiRuntime.scheduleEngine`); one coroutine + one `StateFlow` per session; confirmation ships notify-only (always-approve stub, blocked on ADR-016's session-id-less `confirm`); `jpackage` bundling in v1 because macOS notifications need a real `.app` |
| [ADR-023](adr/ADR-023-cli-hub-remote-control.md) | CLI session monitoring & remote control | New `sophi-hub` module (protocol+server+client); companion owns hub lifecycle, CLI registers on by default; `TurnEvent`/`ConfirmationPolicy` reused as the forwarding seam instead of new `AgentHook` points; confirmation races terminal vs. remote, first response wins, no lock |
| [ADR-025](adr/ADR-025-interactive-goal-command.md) | Interactive `/goal` command | Hybrid session visibility (isolated step execution, structured anchor-session record); plan preview via `input.readLine()`, not `awaitYesNo()`; extends ADR-020's `PlanProgressEvent` instead of adding a second event stream; `initialPlan` preview seam; `allowParallelSteps` stays `false`; CLI-only v1 |
| [ADR-024](adr/ADR-024-tot-widened-replan-search.md) | Tree-of-thought widened replan search | **Accepted — probation**, written retroactively: `PlanRunner`'s existing search was already DFS-with-backtracking at width 1, so `TreePlanner` widens one node rather than adding a subsystem; GoT rejected because merge is incoherent over side-effecting executed steps; `Planner` decorator (zero `PlanRunner` diff), `replan()` only, goal-mode only by construction; new `PlanCritic` because `StepCritic` can't score an unexecuted plan; 300s critic timeout because failing open here is a full-price no-op, not a safe degrade; `SOPHI_TOT_SEARCH_ENABLED` kill switch; probation because the mechanism works but value was never demonstrated |

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
| `sophi-skills` invocation (`SkillRegistry`, `SkillTool`, `/skill`) | post-M7 | complete | [article-07](articles/article-07.md) |
| `sophi-skills` installer (`SkillInstaller`, `sophi skill install`, `install_skill`) | post-M7 | complete | [article-07](articles/article-07.md) |
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
| `sophi-memory` — Jane's Theory memory palace | M7 | complete | [article-18](articles/article-18.md) |
| `sophi-schedule` — scheduled & goal-based tasks | post-M7 | complete | [article-19](articles/article-19.md) |
| `sophi-calendar` — native OS calendar integration (macOS) | post-M7 | in progress | [article-21](articles/article-21.md) |
| `sophi-core`/`sophi-schedule` — tiered tool confirmation & grants | post-M7 | design | [article-22](articles/article-22.md) |
| `sophi-core` auto mode + hybrid risk classifier | post-M7 | complete | [article-23](articles/article-23.md) |
| `sophi-core`/`sophi-schedule` — Plan-and-Execute upgrade | post-M7 | complete | [article-24](articles/article-24.md) |
| `sophi-cli` — interactive `/goal` command | post-M7 | complete | — |
| `sophi-core` — `invoke_claude_code` tool | post-M7 | complete | — |
| `sophi-companion` — OS tray desktop app embedding `sophi-sdk` | post-M7 | complete | [article-25](articles/article-25.md) |
| `sophi-core`/`sophi-schedule` — `TreePlanner` widened replan search | post-M7 | probation | — |
