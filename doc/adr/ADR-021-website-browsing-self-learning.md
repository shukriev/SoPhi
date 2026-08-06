# ADR-021: Website browsing via existing MCP servers + self-authored site skills

**Date:** 2026-08-06
**Status:** Accepted — implemented

## Context

The goal is for Sophi to browse real websites and get better at operating a given
site over repeated visits — recall a known workflow instead of re-exploring, and
record what it learns for next time. Three existing systems cover most of this
already: the MCP client (`sophi-mcp`) connects to any MCP server generically, goal
mode (`PlanRunner`, ADR-018) executes multi-step goals through the normal
`AgentLoop`, and the 4-phase learning system (ADR-008..012) observes tool calls via
hooks. What's missing is a durable, site-specific artifact — the generic lesson
system (Phase 2, ADR-010) is unstructured and the wrong shape for "the login form is
at `/auth/login`."

A design review (spec `docs/superpowers/specs/2026-08-06-website-browsing-self-learning-design.md`)
found that an initial draft's claim of "goal mode needs zero code changes" was false
in two places, verified against the actual code: `PlanRunner.executeOnce()` called
`agentLoop.turn()` with no `onEvent`, so goal-mode tool calls never reached the
learning system's hooks; and `DecomposeGoalTool` passed `expected_tools` straight
through as `AgentLoop` grants, which bypass confirmation entirely regardless of risk
tier — meaning a plan that merely expected to use a destructive tool got it
blanket-approved for the whole run, not confirmed per call.

## Decision

1. **Reuse an existing browser-automation MCP server, not a bespoke `sophi-browser`
   module.** `sophi-mcp`'s client already exposes any configured server's tools
   generically; a dedicated module would duplicate that and add a new native
   dependency for no benefit over configuration. `safeTools` in `.sophi/mcp.json`
   classifies read-only actions (navigate, screenshot, snapshot) as safe; interaction
   actions (click, type, submit) stay `DESTRUCTIVE`.
2. **`WriteSkillTool` (new, `sophi-cli`), namespaced to `site-*`.** `FileWriteTool` is
   deliberately CWD-sandboxed and cannot reach `~/.sophi/skills/`, where a
   cross-project site skill needs to live. Follows the existing
   `InstallSkillTool`/`SkillInstaller` global-vs-project-dir pattern. The `site-*` id
   requirement is the only thing preventing an overwrite of the meta-skill itself or a
   hand-authored global skill, since `SkillMetadata` has no separate `id` field from
   the filename.
3. **`PlanRunner` gains an `onEvent` bridge**, threaded to every step's
   `AgentLoop.turn()` call, wired at both the interactive `/plan` path (`sophi-cli`)
   and scheduled Goal-mode runs (`sophi-schedule`, which gained a new optional
   `PluginRegistry` dependency for this). This is a correctness fix for goal mode
   generally, not specific to browsing — surfaced here because browsing was the
   first goal-mode use case where it mattered whether the learning system actually
   observed the run.
4. **`DecomposeGoalTool`'s `grants` are filtered to `SAFE`-only tools.** Fixed
   generically rather than special-cased for browser/MCP tools, at the user's
   explicit choice — a `DESTRUCTIVE` tool an LLM-generated plan merely expects to use
   can no longer be blanket-approved without per-call confirmation, in any goal-mode
   run system-wide. `ScheduleEngine`'s separate `task.toolGrants` mechanism (a
   human-configured allowlist at scheduled-task-creation time, under a `DENY_ALL`
   baseline) is intentionally left unchanged — that grant is explicit and reviewed,
   unlike `DecomposeGoalTool`'s LLM-inferred one.
5. **The explore-then-learn protocol lives in a hand-authored skill
   (`docs/skills/browsing-sites.md`), not in `PlanRunner` code.** Each `PlanStep`
   runs in an isolated child session with no shared state beyond text output
   (`withDependencyContext`), so the protocol is written to be self-contained per
   step rather than assuming a particular plan shape — it works because
   `AgentLoop.turn()` already loops internally over multiple tool-call rounds within
   one step.
6. **`navigate`/`screenshot` are recommended as `safeTools`, with the risk stated
   explicitly.** `safeTools` matches by tool name only — there is no argument
   inspection until ADR-016's tiered, argument-aware risk model ships. Marking
   navigate safe means Sophi can reach `file://` paths and internal-network addresses
   without confirmation, unlike `FetchUrlTool` (which stays `DESTRUCTIVE` for exactly
   this SSRF-shaped reason). Accepted as an explicit, user-approved trade-off; the
   only lever available today is moving `navigate` out of `safeTools`.

## Consequences

- Goal-mode runs — not just browsing ones — now produce learning-system observations
  (tool events, eventually session self-eval and distilled lessons) that they did not
  before this ADR.
- Any existing goal-mode usage relying on `expected_tools` to blanket-approve a
  `DESTRUCTIVE` tool without confirmation will now be prompted per call instead. This
  is the one deliberate, user-approved behavior change with blast radius beyond
  browsing.
- No new module, no new native (browser binary) dependency in the Sophi repo itself —
  browser automation is entirely delegated to whichever MCP server the user
  configures.
- Explicitly out of scope: which browser-automation MCP server to run (left to the
  user); per-argument/tiered risk for browser tools (blocked on ADR-016); cross-site
  generalization; retrieval-ranked skill recall (the skill index stays a flat,
  every-turn list, same as every other skill today).

## References

- ADR-018 (plan-and-execute) — the `PlanRunner` this design plugs into and patches.
- ADR-016 (tiered tool confirmation) — designed, not implemented; the reason
  `safeTools` is name-only and `navigate` can't be scoped to exclude `file://`/internal
  addresses.
- ADR-017 (auto mode hybrid risk classifier) — unchanged; still governs unattended
  confirmation decisions for whatever isn't a grant.
- ADR-008..012 (the 4-phase learning system) — the hooks this ADR's `onEvent` wiring
  makes reachable from goal mode.
- `docs/superpowers/specs/2026-08-06-website-browsing-self-learning-design.md` — full
  design rationale, including the Opus-review findings that shaped decisions 3 and 4.
