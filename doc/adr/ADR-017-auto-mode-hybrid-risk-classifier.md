# ADR-017: Auto mode with hybrid risk classifier

**Date:** 2026-07-30
**Status:** Accepted

## Context

`AgentLoop`'s tiered confirmation mechanism — `RiskLevel` (`SAFE`/`CAUTION`/`DESTRUCTIVE`),
`ConfirmationPolicy`, and the `grants` allow-list, cited in `README.md` as "ADR-016" though no
such file exists yet in `doc/adr/` — gates every non-`SAFE` tool call behind a human prompt. That
gate is coarse: it's a fixed property of the *tool*, not the specific call. A single-file `rm` in
a scratch directory and `rm -rf /` both land in `BashTool`'s `DESTRUCTIVE` bucket and both always
prompt, and there was no persisted "auto mode" concept at all — every CLI session starts fully
manual, same as Claude Code before its own auto-accept mode.

Users want an opt-in mode where genuinely low-risk calls — even within `CAUTION`/`DESTRUCTIVE` —
run without a prompt, while anything that could cause real, hard-to-reverse damage still asks.

## Decision

1. **A new `ConfirmationPolicy`, not a change to `AgentLoop`.** `AutoModeConfirmationPolicy`
   wraps an existing "real" policy (`TerminalConfirmationPolicy` on the CLI) and implements the
   same `ConfirmationPolicy` interface `AgentLoop` already calls. `AgentLoop` needed zero changes
   — it already just calls `confirmationPolicy.confirm(requests)` on whatever it was constructed
   with, so this composes with the existing gate instead of replacing it.
2. **Hybrid classification: rules first, LLM fallback only when ambiguous.** Each `Tool` gets a
   new `ruleVerdict(argumentsJson): RuleVerdict` method (`LOW_RISK`/`HIGH_RISK`/`UNKNOWN`,
   defaulting to `UNKNOWN`), mirroring the existing `riskLevel()` pattern so tool-specific risk
   knowledge stays next to the tool itself (bash command patterns in `BashTool`, path checks in
   `FileWriteTool`/`EditTool`, etc). A separate `RiskClassifier.classify()` (`LlmRiskClassifier`
   in production, backed by a single non-streaming `LLMProvider.complete()` call) is only
   consulted when a tool has no rule opinion. Deterministic rules stay fast, predictable, and
   auditable for the common cases; the LLM handles novel commands rules haven't been written for.
3. **Fail-safe, not fail-open.** Any classifier failure — timeout, provider error, malformed
   response — resolves to `HIGH_RISK`, forwarding to the human-facing fallback policy exactly as
   if auto mode weren't enabled for that call. Auto mode never silently approves on doubt.
4. **A mutable wrapper for runtime toggling, not a stateful policy.** `AutoModeConfirmationPolicy`
   itself stays stateless (constructed once); `ToggleableConfirmationPolicy` holds the on/off
   switch as a `@Volatile var` and delegates to either the auto or manual policy per call. This
   lets the CLI's `/auto` command flip auto mode mid-session without rebuilding `AgentLoop`,
   while `--auto` at launch just sets the wrapper's initial state.
5. **CLI-only for this iteration; the classes are surface-agnostic.** sophi-web and mcp-serve keep
   their current `DENY_ALL` default. `AutoModeConfirmationPolicy`/`ToggleableConfirmationPolicy`
   depend only on `ConfirmationPolicy`, `ToolRegistry`, and `LLMProvider` — none of which are
   CLI-specific — so either surface can adopt auto mode later without redesign.
6. **No user-editable rule configuration in this iteration.** Rules are hardcoded per tool, same
   as `riskLevel()`. A settings file (this project's first) for user-tunable allow/deny patterns,
   closer to Claude Code's `settings.json` permissions, is deferred to a future iteration.

## Consequences

- New tools default to `UNKNOWN` for `ruleVerdict()`, degrading gracefully to the LLM classifier
  rather than silently auto-approving — no tool becomes auto-approved by omission.
- Auto mode adds one extra LLM round-trip per ambiguous call when enabled; deterministic rules
  are expected to cover the common cases (obvious dangerous patterns, obvious scratch-path
  writes) so this cost is paid only for genuinely novel calls.
- The pre-existing "ADR-016" gap (tiered confirmation from PR #28/#29 was never given its own ADR
  file, only a citation in `README.md:256`) is called out here but intentionally not backfilled
  by this change — it predates and is independent of auto mode.
