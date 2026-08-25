# ADR-028: Shared tool wiring between sophi-cli and sophi-companion

**Date:** 2026-08-25
**Status:** Accepted — implemented

## Context

`sophi-cli` registered a full tool set — builtin file/bash/fetch/search/date tools, calendar,
skill invocation (`skill`/`install_skill`/`write_skill`), subagent delegation
(`delegate_to_subagent`), goal decomposition (`decompose_goal`) — via ad hoc wiring in
`CliRuntime.kt`. `sophi-companion` registered none of it: its only tool sources were MCP servers
enabled in `~/.sophi/mcp.json` and `ScheduleTaskTool` (already registered by
`RuntimeBuilder.schedule()`). The user's request was explicit — give the companion "exactly same
capabilities" as the CLI — which meant either duplicating `CliRuntime.kt`'s wiring inside
`sophi-companion`'s `Main.kt`, or centralizing it so both hosts share one implementation.

Two structural differences between the hosts made a naive port unsafe:

1. **Session shape.** `sophi-cli` has exactly one `AgentSession` per process, so `SubagentTool`
   and `DecomposeGoalTool` could take `parentSessionId` as a constructor parameter, baked in once.
   `sophi-companion` is fundamentally multi-session — one shared `ToolRegistry` serves many
   concurrent chat tabs, each with its own session id. A tool built with one hardcoded
   `parentSessionId` cannot serve companion's shape at all.
2. **Unattended execution.** `sophi-cli`'s builtin file/bash tools scope to the process's current
   working directory, which is fine for an interactive terminal session a user launched
   deliberately. `sophi-companion` is an always-running background app; some tool calls fire from
   unattended scheduled/goal-mode runs with nobody watching. Scoping those tools to some arbitrary
   directory — or to the whole filesystem — was not acceptable by default.

The first draft of this design assumed `sophi-cli` and `ScheduleEngine` already propagated a
session id that `SubagentTool`/`DecomposeGoalTool` could read from coroutine context. Neither did.
Had the naive per-session-context fix shipped without first adding that propagation to all three
turn-launching paths, it would have silently broken `sophi-cli`'s and `ScheduleEngine`'s own
existing subagent-delegation and goal-decomposition features — not just failed to add them to
companion.

## Decision

1. **`RuntimeBuilder` (`sophi-sdk`) is the single place either host assembles its tool set.** Five
   methods were added: `.builtinTools(root, braveApiKey)`, `.subagentDelegation()`,
   `.goalDecomposition(plansDir)`, `.skillTools()` — plus the pre-existing `.schedule(dir)`.
   `.skillTools()` resolves its skill directories from the builder's own `skillsDir` field (default
   `~/.sophi/skills`) plus a fixed `.sophi/skills` project directory, rather than taking them as
   arguments — both hosts already have `skillsDir` at its default, so there was nothing for a
   parameter to override. `sophi-cli`'s `CliRuntime.kt` and `sophi-companion`'s `Main.kt` both call
   the same methods on the same builder; `buildBuiltinTools()` (previously private to `sophi-cli`)
   and `SkillTool`/`InstallSkillTool`/`WriteSkillTool` (previously in `sophi-cli`) moved into
   `sophi-sdk` to make this possible.

2. **`SessionIdContext` moved from `sophi-companion` to `sophi-core`, and is now set on all three
   turn-launching paths.** `sophi-core`'s `SubagentTool`/`DecomposeGoalTool` need to read the
   current turn's session id without taking it as a constructor parameter (companion's multi-session
   shape) — but `sophi-core` never depends on `sophi-companion`, so the context type had to live in
   `sophi-core` for `sophi-core`'s own tools to reference it. Both tools' constructors dropped
   `parentSessionId`; `execute()` now reads `coroutineContext[SessionIdContext]?.sessionId`, erroring
   if absent rather than silently misattributing. Three call sites now wrap their turn in
   `withContext(SessionIdContext(session.id))`: `sophi-cli`'s `TurnController.runTurn`,
   `sophi-schedule`'s `ScheduleEngine.runTask`, and `sophi-companion`'s existing
   `CompanionRuntime.sendMessage` (already doing this for `GuiConfirmationPolicy`, which set the
   precedent this decision follows).

3. **Calendar stays a standalone `sophi-calendar` function, not a `RuntimeBuilder` method.**
   `calendarTools(provider)` and `buildCalendarProvider()` (moved out of `sophi-cli` into
   `sophi-calendar/tools/CalendarTools.kt`) are macOS-only (AppleScript/`Calendar.app`) and would
   pull that platform dependency into `sophi-sdk` for every consumer, including ones that never
   touch a calendar. A host that wants calendar tools calls `calendarTools(buildCalendarProvider())`
   and registers the result itself — both `sophi-cli` and `sophi-companion` now do exactly this.

4. **`SkillTool`/`InstallSkillTool`/`WriteSkillTool` moved to `sophi-sdk`, not `sophi-skills`.**
   The obvious-looking destination was wrong: `doc/Architecture.md` documents (and this repo
   enforces) that `sophi-skills` has no dependency on `sophi-core`, but these three classes
   implement `sophi-core`'s `Tool` interface. `sophi-sdk` already depends on both `sophi-core` and
   `sophi-skills`, so that's where they went instead.

5. **`sophi-companion` gets a sandboxed, configurable `workspaceDir` setting** (`CompanionSettings.
   workspaceDir`, default `~/.sophi/workspace`), passed as `root` to `.builtinTools(...)`. Unlike
   `sophi-cli`, which scopes to the interactive process's cwd, companion's default confines file/bash
   tools to a dedicated directory — opted into a broader one explicitly via the new Settings tab
   field, rather than granted the whole filesystem by accident from an unattended run.

## Consequences

- `sophi-web`'s tool wiring still doesn't go through `RuntimeBuilder` — a third, still-divergent
  surface. Not addressed here; `sophi-web` was out of scope for this parity request.
- `ToolRegistry`'s underlying `MutableMap` is not thread-safe. Pre-existing, and made more likely
  to matter now that `sophi-companion` — already multi-session, already concurrent — registers a
  materially larger tool set through it. Not fixed as part of this work.
- Companion's confirmation flow was independently found, while writing the design spec, to already
  be a real per-session Approve/Deny UI (`GuiConfirmationPolicy` → `CompanionRuntime.
  awaitConfirmation` → `ChatTab.kt`'s `SessionState.NeedsConfirmation`), not the "always-approve
  stub" `doc/Architecture.md`'s ADR-022 row had claimed since that ADR was written — that claim was
  stale, not a description of a gap this work closed. Corrected in the Architecture.md ADR-022 row
  and in `sophi-companion/README.md`.

## References

- ADR-007 (subagent delegation) — `SubagentTool`'s per-session binding, the reason a shared
  companion registry can't reuse `sophi-cli`'s single-session constructor-injection pattern.
- ADR-014 (scheduled & goal-based tasks) — `ScheduleEngine.runTask`, one of the three call sites
  now setting `SessionIdContext`.
- ADR-016 (tiered tool confirmation & grants) — `RiskLevel`/`ConfirmationPolicy`; companion's newly
  registered tools (`bash`, `write_file`, etc.) are gated by the same risk tiers `sophi-cli` uses,
  via the real `GuiConfirmationPolicy` flow noted in Consequences.
- ADR-022 (sophi-companion) — the standalone Gradle app this work extends; its confirmation-flow
  claim corrected here.
- Design spec and implementation plan: `docs/superpowers/specs/
  2026-08-25-companion-cli-tool-parity-design.md` and `docs/superpowers/plans/
  2026-08-25-companion-cli-tool-parity.md` — gitignored/local-only.
