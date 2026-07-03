# ADR-007: Subagent delegation via nested AgentLoop, not a new core primitive

**Date:** 2026-07-04
**Status:** Accepted

## Context

Sophi needed a way for a main agent to delegate a task to a specialized subagent — the orchestrator pattern used by Claude Code's own `Task` tool, and the foundation for a general personalized agent built on the harness. Several decisions had to be made:

1. Does delegation require a new execution primitive in `AgentLoop`, or can it reuse the existing `Tool` contract (ADR-006)?
2. How are subagent "types" (a coder vs. a read-only researcher vs. general-purpose) defined and configured?
3. Should a subagent see the full parent toolset, or a restricted subset?
4. What stops a subagent from delegating to itself indefinitely?
5. Should a subagent's work be inspectable after the fact?

## Decision

Delegation is implemented entirely as a `Tool` (`SubagentTool`), not a change to `AgentLoop`. `AgentLoop.turn()` already loops internally — dispatching tool calls, feeding results back to the LLM — until it produces a final `LLMResponse.Text`. A subagent run has the same shape as one `turn()` call, so `SubagentTool.execute()` just constructs a second `AgentLoop` and calls `turn()` on it once, returning the final text as the tool's result.

Supporting pieces:

- **`AgentDefinition`** — a subagent type's name, description, system prompt, allowed tools, and optional model, parsed from a Markdown file with YAML frontmatter (the same artifact shape as `Skill` in `sophi-skills`). Loaded by `AgentDefinitionLoader` from a directory (`sophi-cli`'s `--agents-dir`, default `~/.sophi/agents`).
- **`ToolRegistry.subset(names)`** — builds a filtered registry containing only the named tools, silently ignoring unknown names. `SubagentTool` uses this to scope a nested run to `AgentDefinition.allowedTools`.
- **Depth guard** — `SubagentTool` carries a `depth` counter (default cap: 3). A subagent can only itself delegate further if its own `allowedTools` explicitly includes the delegate tool's name, in which case the nested registry gets a new `SubagentTool` at `depth + 1`. At the cap, delegation is refused with a direct error string and no LLM call.
- **`parentSessionId`** — an additive field on `AgentSession`/`SessionMeta`/`SessionManager`, persisted by `FileSessionManager` as a `<id>.meta.json` sidecar next to the entry-log JSONL file. Every subagent run gets its own session tagged with the session that spawned it.

## Reasons

1. **No core-loop changes.** `AgentLoop.turn()`'s existing tool-dispatch loop already wraps every call in `runCatching { tool.execute(...) }.getOrElse { "Error: ..." }` (established in ADR-006). A subagent's LLM errors or max-tool-rounds failures propagate through that same path with zero extra exception handling in `SubagentTool` — only a `finally` block to persist the subagent's session regardless of outcome. `PromptBuilder` and the provider layer are untouched.

2. **Markdown+YAML for agent definitions.** `sophi-skills` already established this shape for data-driven capability packages (`Skill`/`SkillLoader`). Reusing it for subagent types means adding or tweaking an agent type is a file edit, not a recompile — consistent with the project's existing "capability packages as plain Markdown" philosophy (see article-07).

3. **`AgentDefinition`/`AgentDefinitionLoader` live in `sophi-core`, not `sophi-skills`.** Although the artifact shape is identical to `Skill`, `sophi-core` currently depends only on `sophi-ai`. Giving it a dependency on `sophi-skills` would mean every embedder of bare `sophi-core` (per the README's "grab what you need" module table) transitively pulls in `sophi-skills` and its `kaml` dependency even when they never touch subagents or skills. The ~15-line frontmatter parser is duplicated into `sophi-core` rather than shared — a deliberate trade-off, revisited only if a third consumer of the same parsing logic appears.

4. **Tool scoping via `subset()`, not a runtime permission check.** A subagent physically cannot call a tool outside its allow-list, because the tool object is never in its registry — least-privilege by construction rather than by convention.

5. **Depth guard plus opt-in recursion, not a blanket ban.** Most agent types are leaves (no further delegation), which is the safe default. Multi-level orchestration is still possible when a definition explicitly asks for it, bounded by a hard cap that fires before any LLM call.

6. **Sequential-only delegation.** Matches `AgentLoop`'s existing synchronous `turn()` model and keeps this addition small. The existing tool-dispatch code already runs parallel tool calls via `coroutineScope { async { ... } }.awaitAll()`, so concurrent subagents are a plausible near-free extension later — deliberately deferred here.

## Consequences

- `sophi-core`'s `pom.xml` corrects its `com.charleskorn.kaml` coordinate from `kaml` (a Kotlin-multiplatform metadata artifact with no JVM classes) to `kaml-jvm`, matching how `sophi-skills` already declares the same library. This was a latent, unused, incorrect declaration — Task 1 of the implementing plan was the first code to actually call `Yaml.default`.
- Session persistence gains a second file per session (`<id>.meta.json`), written only when `parentSessionId` is set. The per-entry JSONL format from ADR-005 is unchanged; old sessions without a sidecar report `parentSessionId = null`.
- `sophi-cli` gains a `--agents-dir` flag. With no `.md` definitions found there, `SubagentTool` is simply not registered — delegation is unavailable rather than an error, so a fresh install behaves exactly as before this ADR.
- This ADR covers only the orchestrator/subagent foundation. Two related, larger pieces were explicitly scoped out for future ADRs: tools that let an agent control the host computer beyond file read/write, and an external trigger gateway (a hermes-agent/openclaw-style front door) that would create sessions from outside a chat session. Both depend on this ADR's foundation existing first.
