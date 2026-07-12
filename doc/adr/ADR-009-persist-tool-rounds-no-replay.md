# ADR-009: Persist tool rounds in sessions, excluded from prompt replay

**Date:** 2026-07-09
**Status:** Implemented (merged 2026-07-10, Phase 1)

## Context

`AgentLoop` persists only USER and ASSISTANT text entries; the tool rounds in between (which tools were called, with what arguments, and what they returned) exist only in memory during a turn. This left an open decision (backlog task #63): should tool rounds be persisted to the session?

The learning system forces the question. Phase 2's session-end evaluator must judge *how* the agent worked, not just what it said; Phase 4's fine-tuning export needs faithful trajectories including `tool_calls`/`tool` messages. Neither is reconstructable after the fact.

The complication is prompt replay: `PromptBuilder.build(session.branch())` maps every entry into the next request's messages. Naively persisting tool rounds would inject them into all subsequent turns' context — changing token costs and, worse, risking malformed requests: providers (Anthropic strictly) require exact `tool_use`/`tool_result` pairing, which `SessionEntry`'s text-first shape cannot yet guarantee across serialization boundaries.

A related gap surfaced by Phase 4: sessions record neither the model nor the system prompt they ran with (both are per-turn `AgentConfig`), so training examples could not include the system prompt.

## Decision

`AgentLoop` appends tool rounds to the session: one ASSISTANT entry per round carrying the serialized calls in `metadata["toolCalls"]`, and one TOOL_RESULT entry per result (with `toolCallId`/`toolName` metadata, which `SessionEntry` and `PromptBuilder` already support). All of these entries are marked `metadata["replay"] = "false"`, and `PromptBuilder.build()` filters such entries out.

In tool-round session entries, `toolCallId`/`toolName` metadata use the empty string to mean "absent"; consumers (Phases 2/4) must treat `""` as absent.

Additionally, `FileSessionManager`'s existing `<id>.meta.json` sidecar gains optional `model` and `systemPrompt` fields, written at session creation.

## Reasons

1. **Sessions become faithful records without changing a single prompt.** Existing prompt reconstruction stays byte-identical (verified by test), token costs are untouched, and no provider pairing rules can be violated — the entries are inert for prompting, live for observability, evaluation, and export.

2. **The data is unrecoverable later.** Tool arguments/results and the config snapshot exist only at execution time. Recording them now is cheap; every future consumer (evaluator, export, a session-inspection UI) gets them for free.

3. **Replay becomes a flag-flip decision, not a migration.** If tool-round replay into future context is ever wanted (it would improve multi-turn coherence at token cost), the `replay` marker localizes the change to `PromptBuilder` plus a careful pairing design — no session-format migration. That design is explicitly deferred.

4. **Sidecar over new file.** The `meta.json` sidecar already exists for `parentSessionId` and is read with `ignoreUnknownKeys`, so old sessions load unchanged and new fields cost no format version bump.
