# ADR-016: Tiered tool-call confirmation and grants

**Date:** 2026-07-27
**Status:** Accepted

## Context

`Tool.riskLevel` has been a static `SAFE`/`DESTRUCTIVE` property since ADR-006, and
`AgentLoop` has gated non-`SAFE` calls behind a `ConfirmationPolicy` since before any
ADR documented it. Building on top of that mechanism for subagents (ADR-007),
scheduled tasks (ADR-014), and calendar tools (ADR-015) surfaced five compounding gaps:
confirmation prompts run sequentially even though tool execution itself is parallel;
a second, unused, differently-failing gate (`PermissionGatePlugin`) exists alongside
the one everything actually uses; bundled or capability-granting tools
(`manage_scheduled_task`, `delegate_to_subagent`) can't express that *proposing* future
or delegated power is itself sensitive, since they're `SAFE` overall; risk can't depend
on arguments, so `bash` is `DESTRUCTIVE` even for a read-only command; and no ADR has
ever recorded the mechanism itself.

## Decision

1. **`RiskLevel` gains a third tier: `SAFE`, `CAUTION`, `DESTRUCTIVE`.** `CAUTION`
   auto-runs when a human is attending the session (interactive CLI) and needs a grant
   or confirmation otherwise; `DESTRUCTIVE` always needs one. `SAFE` is unchanged.
2. **`Tool.riskLevel` becomes `fun riskLevel(argumentsJson: String): RiskLevel`**,
   defaulting to `SAFE`, instead of a static `val`. Most tools ignore the argument and
   return a constant; `bash`, `manage_scheduled_task`, and `delegate_to_subagent`
   inspect it to classify accurately instead of adopting one fixed worst-case tier.
3. **`ConfirmationPolicy.confirm` takes a batch, not a single call.** One
   `List<ConfirmationRequest> -> Map<callId, Boolean>` round-trip per tool-calling
   round, not one round-trip per non-`SAFE` call — matching the round's execution,
   which was already parallel.
4. **Grants**: `AgentLoop` gains a `grants: Set<String>` constructor parameter — tool
   names that scope may run without asking, regardless of tier. Two consumers:
   `ScheduledTask.toolGrants` (renamed/broadened from `destructiveToolAllowlist`) for
   scheduled runs, and an ephemeral grant derived from `delegate_to_subagent`'s new
   `expected_tools` argument for one subagent invocation.
5. **Granting power is itself classified.** `manage_scheduled_task`'s `riskLevel(args)`
   returns `DESTRUCTIVE` exactly when a `create`/`update` call is populating
   `toolGrants`; `delegate_to_subagent`'s returns the worst tier among its declared
   `expected_tools`. This is what makes pre-authorizing a scheduled task's future
   unattended power, or a subagent's delegated power, go through the same human
   confirmation everything else does — instead of being a `SAFE` side door.
6. **`PermissionGatePlugin` is retired.** Unused in every production wiring path, and
   its uncaught-`SecurityException` failure mode was incompatible with the graceful
   tool-error semantics the rest of the mechanism relies on. `PluginRegistry`'s
   `BEFORE_TOOL`/`AFTER_TOOL` hooks remain for other uses; only this one plugin goes.
7. **`AllowlistConfirmationPolicy` is deleted**, folded entirely into `AgentLoop.grants`
   — it added no behavior beyond what a first-class grants parameter now does directly.

## Reasons

1. **Confirmation shouldn't serialize what execution parallelizes.** The round's tool
   calls already dispatch concurrently (`coroutineScope { async { ... } }.awaitAll()`);
   gating them one at a time in front of that was an artifact of the interface shape,
   not a deliberate choice.
2. **One enforcement path is easier to reason about than two with different failure
   modes.** A tool call being silently denied (graceful) versus a whole round dying to
   an uncaught exception (destructive to the turn) are not equivalent safety postures,
   and having both live in the codebase risked someone wiring the wrong one.
3. **The riskiest moment for `manage_scheduled_task`/`delegate_to_subagent` was never
   the tool call that uses power — it was the one that hands it out.** A scheduled
   task's `toolGrants` is consulted with no human present, by definition; the only
   point a human *can* look at it is when it's being set. Classifying the
   grant-creating call itself closes that window using the exact mechanism already in
   place for every other sensitive action, rather than inventing a parallel approval
   step.
4. **Argument-aware classification was the missing piece for `bash`**, named explicitly
   in `TODO_TASK.md`'s "general bash-fallback instruction" item — a per-tool constant
   tier can't tell `git status` from `rm -rf`, and pretending it can by leaving `bash`
   `DESTRUCTIVE` wholesale is friction with no safety benefit for the read-only case.
5. **Grants generalize a pattern the codebase had already reinvented once.**
   `AllowlistConfirmationPolicy` (scheduling) was going to need a sibling for subagents
   under this design; making grants a first-class `AgentLoop` concept means both
   consumers share one implementation and one set of tests instead of two similar but
   separately-maintained wrapper classes.

## Consequences

- `Tool.riskLevel` is now a function on every implementation; existing overrides
  (`FileWriteTool`, `EditTool`, `BashTool`, `FetchUrlTool`, `UpdateCalendarEventTool`,
  `DeleteCalendarEventTool`, `McpTool`) all change from `override val` to `override fun`.
- `create_calendar_event` and provably-read-only `bash` commands move from silently
  `SAFE` to `CAUTION` — unattended contexts (`sophi-web`, `sophi-sdk`, and any
  scheduled task without a matching grant) now deny them where they previously ran
  unconditionally. Intentional tightening, called out in the spec's migration notes.
- Existing `ScheduledTask.destructiveToolAllowlist` values on disk are dropped on
  next load (field renamed to `toolGrants`, absent key defaults to `emptySet()`) —
  tasks become more restrictive, never more permissive, the safe failure direction.
  No migration script; a one-time re-authorization is needed per affected task.
- `ConfirmationPolicy.DENY_DESTRUCTIVE` is renamed to `DENY_ALL` (no back-compat
  alias) — its meaning was always "deny anything not `SAFE`," which now includes
  `CAUTION`.
- `PermissionGatePlugin` and `AllowlistConfirmationPolicy` are deleted, along with
  their tests; the README's `PermissionGatePlugin` example is updated to describe
  `AgentLoop.grants` instead.
- v1's `TerminalConfirmationPolicy` batch UX is allow-all/deny-all for multi-call
  rounds, not per-item — tracked as a fast-follow pending a new `InputSource`
  primitive, not built now.
- No confirmation UI is added to `sophi-web`; it keeps `DENY_ALL` with no grants by
  default (a `.grants(names)` builder method is added to `sophi-sdk`'s
  `RuntimeBuilder` for embedders, mirroring `.confirmationPolicy()`).

## References

- Spec: `docs/superpowers/specs/2026-07-27-tiered-tool-confirmation-design.md`
- ADR-006 (tool interface) — original `Tool` contract, predates `riskLevel` entirely.
- ADR-007 (subagent delegation) — `ToolRegistry.subset()`/depth-limit machinery that
  `expected_tools`-derived grants sit alongside, not replace.
- ADR-014 (scheduled & goal tasks) — introduced `AllowlistConfirmationPolicy`, folded
  into `AgentLoop.grants` here.
- ADR-015 (native OS calendar integration) — first explicit statement of the
  per-tool-`riskLevel`-gates-confirmation design intent, and the bundled-tool
  risk-granularity limitation this ADR resolves for `manage_scheduled_task`.
- `TODO_TASK.md` — "general bash-fallback instruction" item, the concrete gap that
  motivated argument-aware `riskLevel`.
