# Agent Toolset Expansion Design

**Date:** 2026-07-06
**Status:** Approved
**Scope:** Six new tools (`grep`, `glob`, `edit_file`, `bash`, `fetch_url`, `web_search`) plus the risk/confirmation plumbing they need, across `sophi-core`, `sophi-ai`, and the three run surfaces (`sophi-cli`, `sophi-web`, `sophi-sdk`).

---

## Context

Today the agent's only tools are `read_file`, `write_file` (both whole-file, `FileReadTool`/`FileWriteTool`), and `delegate_to_subagent` (`SubagentTool`). There is no way for the agent to search the working directory, make a targeted edit, run a shell command, or look anything up on the web — all things Claude Code's own toolset provides. This spec closes that gap.

`AgentConfiguration` (sophi-web) and `RuntimeBuilder` (sophi-sdk) both start from an empty `ToolRegistry` and let the host wire in whichever tools it wants; `sophi-cli`'s `SophiCli.kt` is the only place that currently registers tools eagerly. There is no existing risk-tiering or confirmation concept anywhere in the codebase — `write_file` executes unconditionally today, and this spec does not change that. `sophi-extensions` has an `AgentHook`/`HookPoint.BEFORE_TOOL`/`AFTER_TOOL` mechanism, but it is fire-and-forget (`suspend fun invoke(context): Unit`, no veto) and isn't wired into `AgentLoop` yet, so it isn't a fit for gating destructive tool calls — this spec introduces a separate, purpose-built mechanism for that.

All six tools stay in `sophi-core/tools` (`SearchProvider`/`BraveSearchProvider` in `sophi-ai`), consistent with where `FileReadTool`/`FileWriteTool` already live. None of them need a dependency `sophi-core` doesn't already have transitively — `grep`/`glob` are pure `java.nio.file` + `Regex`, `edit_file` is string manipulation, `bash` is `ProcessBuilder`, and the web tools use the JDK's built-in `java.net.http.HttpClient`. So there's no dependency-isolation reason to create a new module.

---

## 1. Risk levels and confirmation

### `Tool.riskLevel` (change to `sophi-core/tools/Tool.kt`)

```kotlin
enum class RiskLevel { SAFE, DESTRUCTIVE }

interface Tool {
    val name: String
    val description: String
    val parametersJson: String
    val riskLevel: RiskLevel get() = RiskLevel.SAFE
    suspend fun execute(argumentsJson: String): String
}
```

Default is `SAFE`, so `FileReadTool`, `FileWriteTool`, and `SubagentTool` need no changes and keep their exact current behavior. Of the six new tools, `edit_file` and `bash` override this to `DESTRUCTIVE`; `grep`, `glob`, `fetch_url`, `web_search` stay `SAFE`.

**`write_file` is deliberately not reclassified in this pass.** It keeps running unconfirmed, exactly as today — reclassifying it would silently change existing behavior on every surface that already registers it. Once the confirmation flow below has seen real usage, unifying `write_file` with `edit_file` under `DESTRUCTIVE` is a natural, small follow-up.

### `ConfirmationPolicy` (new — `sophi-core/tools/ConfirmationPolicy.kt`)

```kotlin
fun interface ConfirmationPolicy {
    suspend fun confirm(toolName: String, argumentsJson: String): Boolean

    companion object {
        val ALLOW_ALL: ConfirmationPolicy = ConfirmationPolicy { _, _ -> true }
        val DENY_DESTRUCTIVE: ConfirmationPolicy = ConfirmationPolicy { _, _ -> false }
    }
}
```

Being a `fun interface` makes it a one-line fake in tests (`ConfirmationPolicy { _, _ -> true }`).

### `AgentLoop` change

`AgentLoop` gains an optional constructor parameter:

```kotlin
class AgentLoop(
    private val provider: LLMProvider,
    private val registry: ToolRegistry,
    private val sessionManager: SessionManager,
    private val compactor: ContextCompactor? = null,
    private val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.ALLOW_ALL
)
```

Defaulting to `ALLOW_ALL` means every existing construction site (`SophiCli`, `AgentConfiguration`, `RuntimeBuilder`, tests) keeps compiling and behaving exactly as it does today until it opts into a real policy.

Inside the `LLMResponse.ToolUse` branch (`AgentLoop.kt:59-87`), before the existing `coroutineScope { ... async ... awaitAll() }` dispatch, resolve confirmation **sequentially** (plain `.map`, not `async`) for any call whose tool is `DESTRUCTIVE`:

```kotlin
val decisions = response.calls.map { call ->
    val tool = registry.getOrNull(call.name)
    val allowed = tool == null || tool.riskLevel == RiskLevel.SAFE ||
        confirmationPolicy.confirm(call.name, call.argumentsJson)
    call to allowed
}
```

Doing this sequentially — before any concurrent execution starts — avoids a batch of tool calls producing several overlapping interactive prompts at once. The existing concurrent `async`/`awaitAll` dispatch is unchanged, except a denied call short-circuits to `"Error: Tool '${call.name}' execution denied by confirmation policy"` instead of calling `tool.execute(...)`, so the LLM sees the refusal in its next turn and can adapt (e.g. ask the user, or try a different approach) instead of the turn silently failing.

### Per-surface wiring

- **sophi-cli**: registers all six new tools and constructs `AgentLoop` with a `ConfirmationPolicy` that prints the pending tool name + arguments and prompts y/n through the existing terminal input path (same `SophiTerminal`/JLine machinery already used for user input).
- **sophi-web** (`AgentConfiguration.kt`): registers all six tools in the `toolRegistry()` bean; adds a `ConfirmationPolicy` bean defaulting to `ConfirmationPolicy.DENY_DESTRUCTIVE`, overridable by a consuming Spring app via normal bean override.
- **sophi-sdk** (`RuntimeBuilder.kt`): adds `fun confirmationPolicy(policy: ConfirmationPolicy): RuntimeBuilder`, defaulting to `ConfirmationPolicy.DENY_DESTRUCTIVE` — a host app must explicitly opt in for `edit_file`/`bash` to ever actually run.

This means `read_file`/`write_file`/`delegate_to_subagent` behave identically to today on every surface; only the two new `DESTRUCTIVE` tools require a surface to make an explicit choice, and until it does, they safely no-op with a clear error the LLM can see.

---

## 2. Search tools: `grep` and `glob`

Both `SAFE`, both pure JVM (no shelling out to the system's `grep`/`find`), so behavior is identical across CLI/web/SDK regardless of host OS or container — a requirement given these tools may run in different deploy environments. Both default-skip `.git`, `build`, `target`, `node_modules`, `.gradle` when walking.

### `GrepTool` (`sophi-core/tools/GrepTool.kt`)

- Params: `pattern` (regex), `path` (optional subdirectory, defaults to working-directory root), `filePattern` (optional glob filter applied to filenames, e.g. `*.kt`), `maxResults` (default 200).
- Implementation: `Files.walk(root)`, filter regular files (and `filePattern` if given), read each as text, apply `Regex(pattern)` line by line.
- Returns matches as `relative/path:lineNumber: line content`, one per line, capped at `maxResults` with a trailing `"... N more matches"` note if truncated.
- Same root-jail (`resolved.startsWith(root)`) as `FileReadTool`.

### `GlobTool` (`sophi-core/tools/GlobTool.kt`)

- Params: `pattern` (glob syntax, e.g. `**/*.kt`), `path` (optional root).
- Implementation: `FileSystems.getDefault().getPathMatcher("glob:$pattern")` + `Files.walk`.
- Returns a capped, alphabetically sorted list of relative paths (one per line).

---

## 3. `edit_file`

`DESTRUCTIVE`. Params: `path`, `old_string`, `new_string`, `replace_all` (optional, default `false`).

Logic:
1. Read the file at `root.resolve(path)` (same root-jail as `FileReadTool`/`FileWriteTool`).
2. Count occurrences of `old_string`.
3. `0` occurrences → return an error: not found.
4. `>1` occurrences and `!replace_all` → return an error asking for more surrounding context or `replace_all: true`.
5. Otherwise replace (all occurrences if `replace_all`, else the single occurrence) and write the file back.

This mirrors Claude Code's own `Edit` tool semantics — exact-match, uniqueness-checked, no line-number-based patching (which is fragile across concurrent edits).

---

## 4. `bash`

`DESTRUCTIVE`. Params: `command` (string), `timeoutSeconds` (optional, default 120, hard-capped at 300 regardless of what's requested).

Implementation: `ProcessBuilder(shell, "-c", command)` with:
- `directory(root.toFile())` — cwd pinned to the working-directory root, same jail concept as the file tools.
- Combined stdout+stderr captured and capped at 100KB (truncated with a `"... output truncated"` note), consistent with the existing `MAX_FILE_BYTES`/`MAX_CONTENT_BYTES` pattern in the file tools.
- A timeout that destroys the process (and its descendants, via `ProcessHandle`) if exceeded, returning whatever output was captured plus an error noting the timeout.

No command allowlist/denylist — per the chosen design direction, gating destructive execution is entirely `ConfirmationPolicy`'s job, not a static config list baked into the tool itself. Environment is inherited from the host process (no stripping), since this is a coding-assistant tool that needs the same `PATH`/toolchain the user has.

---

## 5. Web tools: `fetch_url` and `web_search`

### `FetchUrlTool` (`sophi-core/tools/FetchUrlTool.kt`)

`SAFE`. Params: `url`. Uses `java.net.http.HttpClient`, GET only, `http`/`https` schemes only, response body capped at 500KB (truncated with a note).

**SSRF guard (hard rule, not confirmation-gated):** since this tool can run server-side on sophi-web/sophi-sdk, it must reject any URL whose resolved host falls in a loopback/private/link-local range — `127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16` — before making the request. This stops a malicious or confused prompt from making the agent probe internal services or cloud metadata endpoints (e.g. `169.254.169.254`). This check is structural (always enforced), not something `ConfirmationPolicy` can be asked to allow.

### `SearchProvider` (new — `sophi-ai/api/SearchProvider.kt`)

Mirrors the existing `LLMProvider` abstraction:

```kotlin
interface SearchProvider {
    val name: String
    suspend fun search(query: String, count: Int): List<SearchResult>
}

data class SearchResult(val title: String, val url: String, val snippet: String)
```

### `BraveSearchProvider` (new — `sophi-ai/providers/BraveSearchProvider.kt`)

The one built-in implementation, calling the Brave Search API over `java.net.http.HttpClient` with the API key read from `BRAVE_SEARCH_API_KEY` (env var) or a config property — the same fallback pattern `buildProviderFromProperties` already uses for `ANTHROPIC_API_KEY`.

### `WebSearchTool` (`sophi-core/tools/WebSearchTool.kt`)

`SAFE`. Params: `query`, `count` (default 5). Takes a `SearchProvider` as a constructor dependency and formats its results as a numbered list of `title / url / snippet`.

---

## 6. Testing

Follows the existing `FileReadToolTest`/`FileWriteToolTest` convention: construct the tool directly against a temp-directory root, call `execute()` with hand-built JSON, assert on the returned string — no LLM mocking needed for tool logic itself.

- `AgentLoop`'s confirmation branch is tested with fake `ConfirmationPolicy` lambdas (`{ _, _ -> true }` / `{ _, _ -> false }`) and a fake `DESTRUCTIVE` test tool.
- `BashTool` tests use real short-lived subprocesses (`echo hi`, `sleep` for the timeout case) rather than mocking `ProcessBuilder`.
- `FetchUrlTool`/`WebSearchTool` tests use a fake `HttpClient`/`SearchProvider` — no live network calls in the unit test suite.

---

## Out of scope / follow-ups

- Reclassifying `write_file` as `DESTRUCTIVE` (noted above) — deferred until the confirmation flow is validated in real use.
- Task/todo-tracking tool (Claude Code's `TaskCreate`/`TaskUpdate` equivalent) — not included in this pass.
- Full OS-level sandboxing (containers, network-disabled subprocess) for `bash` — this spec uses process-level guardrails only (cwd jail, timeout, output cap); revisit if sophi-web/sophi-sdk ever needs to run genuinely untrusted, multi-tenant workloads.
- Wiring `AgentHook`'s `BEFORE_TOOL`/`AFTER_TOOL` into `AgentLoop` — currently unwired and orthogonal to this spec's confirmation mechanism; left for whoever picks up the plugin system next.
- DNS-rebinding residual risk in `fetch_url` — the SSRF guard resolves a hostname once via `InetAddress.getByName()` to verify it's not private/loopback, but `HttpClient` re-resolves independently at connect time; an attacker controlling DNS could return a public IP at check time and a private/loopback IP at connection, bypassing the guard. Accepted as low-risk for a dev-tool agent pending IP-pinning feasibility improvements in the JDK; revisit if exposed to less-trusted callers.
- Deny-by-default tool advertisement tradeoff on sophi-web/sophi-sdk — `bash` and `edit_file` are registered in the `ToolRegistry` but with `ConfirmationPolicy.DENY_DESTRUCTIVE`, causing the LLM to see and attempt these tools that it can never successfully execute; consider not registering these tools on surfaces where the policy is deny-by-default and unlikely to be overridden, to avoid wasted tool-call attempts.
