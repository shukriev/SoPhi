# ADR-008: Learning lives in a separate `sophi-learning` module observing via activated tool hook points

**Date:** 2026-07-09
**Status:** Accepted (implementation pending — specs in `docs/superpowers/specs/2026-07-09-learning-phase*.md`)

## Context

Sophi is getting a four-phase learning system (outcome capture → lesson distillation → preference learning → trajectory export). The machinery needs to observe every tool execution and every turn. Three architectures were considered:

- **A.** A new `sophi-learning` module observing through the existing plugin/hook system (`sophi-extensions`), the pattern `sophi-infra`'s `MetricsPlugin` already uses.
- **B.** Building learning into `sophi-core` (a `ToolRegistry` decorator, stores in core).
- **C.** Offline mining only — a command that parses session files after the fact, with no runtime coupling.

Complication for A: `HookPoint.BEFORE_TOOL`/`AFTER_TOOL` exist in the enum but are never dispatched. `SophiRuntime.turn()` fires only BEFORE_TURN/AFTER_TURN/ON_ERROR, and `AgentLoop` has no plugin awareness (`sophi-core` does not depend on `sophi-extensions`). However, `AgentLoop` already emits `TurnEvent.ToolCallStarted/Finished` through its `onEvent` callback — which `SophiRuntime` currently discards.

## Decision

Approach A. A new Maven module `sophi-learning` (depends on `sophi-core` + `sophi-extensions`) holds all stores, the evaluator, and the export pipeline. It observes the agent as a `SophiPlugin`.

The dead tool hook points are activated by **bridging existing turn events**: a `PluginRegistry.turnEventBridge(sessionId)` extension converts `ToolCallStarted/Finished` into BEFORE_TOOL/AFTER_TOOL dispatches. `SophiRuntime.turn()` passes the bridge as `onEvent`; `sophi-cli` and `sophi-web` compose it with their existing `onEvent` handlers. Supporting additive enrichment: `TurnEvent.ToolCallFinished` gains `isError`/`durationMillis` (defaults preserve existing consumers), `HookContext` gains optional `argumentsJson`/`toolResult`/`success`/`durationMillis`.

Learning data is stored globally under `~/.sophi/learning/` with every record tagged with a `scope` (the working directory), rather than per-project stores.

## Reasons

1. **Core stays minimal.** `sophi-core` remains dependency-light and learning-free; embedders who don't want learning never pull it in — consistent with the README's "grab what you need" module philosophy and the boundary discipline of ADR-001/ADR-007. Approach B was rejected for forcing learning on every embedder.

2. **No new observation mechanism.** The plugin/hook system exists precisely for cross-cutting observers (`MetricsPlugin`, `PermissionGatePlugin`). Bridging `TurnEvent` → hooks activates enum values that were designed for this and never wired, instead of inventing a parallel event path. The only core change is additive fields on an event that `AgentLoop` already emits with the data in hand (`runCatching` result, denial branch).

3. **Approach C can't see what isn't recorded.** Sessions today persist only user/assistant text — no tool rounds, durations, or denial events — so offline mining alone has nothing to mine at tool granularity. Its one genuinely good idea (zero runtime coupling for batch work) survives as Phase 4's export command, which reads only files the other phases write.

4. **Global store + scope tag over per-project stores.** A lesson learned anywhere ("the user prefers conventional commits") is usually portable; hard project isolation would relearn it per repo. Scope tags give per-project filtering *and* global recall, and a single learning home keeps the transparency commands (`sophi lessons`, `sophi feedback`) trivial.

5. **Storage follows ADR-005.** Append-only JSONL logs with rebuildable in-memory aggregates — no database, no native dependencies, torn lines skipped on read. Same durability philosophy as sessions.
