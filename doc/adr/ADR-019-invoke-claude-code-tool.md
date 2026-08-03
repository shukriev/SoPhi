# ADR-019: `invoke_claude_code` tool for per-ticket implementation

**Date:** 2026-08-03
**Status:** Accepted — implemented

## Context

A Trello-ticket-driven implementation pipeline needs to hand a well-scoped task off to an
autonomous coding session. The original design considered a new `sophi-tickets` module with
bespoke workspace provisioning and a hand-rolled orchestration loop — superseded by reaching
this entirely with existing pieces (`bash`, the Trello MCP tool, a Skill, and
`sophi-schedule`'s Goal-mode task, which runs through `PlanRunner` per ADR-018) plus exactly
one new Tool.

## Decision

1. **One Tool, no new module.** `RunClaudeCodeTool` lives in `sophi-core/tools/` alongside
   `BashTool`, registered in the existing shared `buildBuiltinTools` — already called by both
   `sophi-cli`'s interactive session and `sophi-schedule`'s scheduled tasks, so no separate
   wiring is needed for the unattended case this tool exists for.
2. **`riskLevel`/`ruleVerdict` are both hardcoded `DESTRUCTIVE`/`HIGH_RISK`**, never
   argument-dependent. This tool spawns an entire autonomous coding agent — it must never be
   silently auto-approved by a risk classifier's guess under auto mode. The only way it runs
   unattended is an explicit `toolGrants` entry a human sets up once, consciously, when
   creating the scheduled ticket-pipeline task (ADR-016's "ask once, not per call").
3. **The nested session's own trust level is a separate, second gate**: `--permission-mode
   auto` (verified against the real CLI, not guessed), not `bypassPermissions`. Two
   independent gates — the outer `toolGrants` decision and the inner permission mode — neither
   alone is sufficient, matching the design spec's explicit reasoning.
4. **No orchestration code.** The per-ticket decomposition ("for each card, provision a
   workspace, then call this tool") is left to `PlanRunner`'s own planning, driven by a Skill
   (Markdown workflow guidance), not written in Kotlin.
5. **Output read concurrently with `waitFor()`**, matching `BashTool`'s existing pattern — a
   sequential read-after-wait risks a pipe-buffer deadlock once output exceeds the OS pipe
   buffer size.

## Consequences

- No new orchestration surface to maintain — `sophi-schedule`'s existing Goal-mode task
  mechanism is the entire "loop over tickets" implementation.
- The nested Claude Code session's own actions are still gated by its own `--permission-mode
  auto`, not fully unattended — a ticket needing an action that mode won't auto-approve will
  stall rather than complete, a deliberate conservative tradeoff (see the design spec's risk
  summary).
- Not yet empirically verified: whether `claude -p --permission-mode auto` fails safe to
  *deny* an action its classifier can't confidently decide, given there is no TTY to prompt
  on in headless mode. The tool's own `timeout_seconds` wall-clock cap is the backstop
  regardless, but this should be tested directly once a real ticket-pipeline task runs.

## References

- `docs/superpowers/specs/2026-08-03-invoke-claude-code-tool-design.md` — full design and the
  two-gate risk reasoning.
- `docs/superpowers/specs/2026-08-03-trello-mcp-integration-design.md` — the companion
  Trello-reading piece this tool is meant to be used alongside.
- ADR-016 (tiered tool confirmation & grants) — the `toolGrants` mechanism this tool's safety
  model depends on entirely.
- ADR-018 (plan-and-execute) — `PlanRunner`, which does the actual per-ticket decomposition
  this design deliberately writes no orchestration code for.
