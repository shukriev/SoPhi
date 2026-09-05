# ADR-032: Shadow-PR self-modification

**Date:** 2026-09-06
**Status:** Proposed — design brainstormed and approved in chat, not yet planned or implemented.

## Context

`docs/superpowers/specs/2026-08-18-autonomous-self-improvement-roadmap.md` lists "a general
agent-modifies-arbitrary-source-code capability" as explicitly out of scope for Phases 0–3.
`propose_improvement`/`ProposalStore` (ADR-027) already let the agent flag an idea and a human
review it via `sophi proposals accept`/`reject`, but acceptance today does nothing beyond flipping
a status field — nothing turns an accepted proposal into an actual code change. Separately, the
ADR-029 eval-tournament can score a *config* variant against an incumbent (same JVM, same jar, only
`HarnessConfig` fields differ) but has no mechanism to evaluate a *code* variant, and no
git-worktree, build-orchestration, or `gh`/PR-creation infrastructure exists anywhere in the Kotlin
sources today (confirmed by full-repo grep before this design was written) — this is genuinely new
ground, not a rename or extension of anything already shipped.

This ADR does not walk back the roadmap's non-goal. It proposes a narrowly-gated capability that
sits *after* the roadmap's existing propose-then-confirm surface, bounded by three properties none
of which are configurable away: it only ever acts on a proposal a human already accepted, it never
merges anything (merging stays a separate, later, entirely human action on GitHub), and it runs
inside a disposable worktree, never the working tree.

## Decision

1. **Double human gate, not a new autonomous-action path.** `sophi proposals implement <id>`
   requires `status == accepted` — set only by a human running `sophi proposals accept` — and
   requires `SOPHI_SHADOW_PR_ENABLED=true` (unset/false = OFF, matching every other phase's
   fail-safe-toward-OFF kill switch). Opening the PR is as far as this flow ever goes autonomously;
   merging is a second, separate human gate on GitHub, not a configuration option.
2. **The code-change engine is `RunClaudeCodeTool` (ADR-019), unchanged, pointed at a disposable
   worktree.** No new coding-agent capability is built. `RunClaudeCodeTool` is already
   unconditionally `riskLevel = DESTRUCTIVE`/`ruleVerdict = HIGH_RISK` (never argument-dependent),
   so the existing `TerminalConfirmationPolicy` already gates it — reused as-is, per ADR-016's
   tiered-confirmation model.
3. **A mechanical safety diff-scan gates the build, not an LLM judgment call.** Before any build is
   attempted: a path denylist (`.github/workflows/`, `.git/`, CI/secret-adjacent globs), a size cap,
   and an empty-diff check. Mirrors ADR-030's `checkSkillContent` precedent — static, pre-write
   checks rather than trusting the coding step's own output.
4. **Build+test and a new eval-tournament "code mode" both gate PR creation.** `mvn test` runs
   inside the worktree. Separately, `Tournament.evaluateAcceptance` (ADR-029) — already pure math
   over two score sets — is reused unchanged, but the two score sets now come from running the
   existing `EvalCase`/`runSuite` harness **as two separate subprocesses**, once against a jar built
   from `main` HEAD and once against a jar built from the worktree, same fixed `HarnessConfig` and
   suite both times. This is the one genuinely new mechanism this ADR introduces: it lets the
   existing regression-cap/noise-floor math express "same config, different code," which the
   in-process tournament runner structurally cannot (same JVM, same classes). A regression or a
   `requiresManualReview` result aborts — the worktree is left in place for manual inspection, not
   deleted or silently discarded.
5. **Opening the PR is its own separately-confirmed, unconditionally-`DESTRUCTIVE` step.** Distinct
   confirmation from step 2's — running the coding step and opening a PR against shared remote state
   are different decisions and get different prompts, following the same "never
   argument-dependent" classification precedent `RunClaudeCodeTool` already established.
6. **Every run is audited independently of any database**, appending to a plain
   `~/.sophi/schedule/shadow-pr-audit.jsonl` — same reasoning as `audit.jsonl` elsewhere in this
   program: an audit trail must survive even a corrupted or unreachable store, more so once the
   agent is modifying itself. `Proposal.status` gains two new terminal values (`pr-opened`,
   `implementation-failed`), recorded the same append-only way existing transitions already are.
7. **No new module.** The whole flow lives in `sophi-cli` (new `ProposalImplementCommand` plus a
   small `ShadowPrWorktreeManager`), since every dependency it needs is already reachable from
   `sophi-cli`'s position at the top of the module graph and the trigger is explicitly human-run in
   v1, not scheduled/unattended. Extracting a shared module is deferred until an unattended trigger
   is actually designed — building that abstraction now would be speculative.
8. **A wall-clock budget bounds the whole flow** (default ~20 minutes, configurable); exceeding it
   kills any still-running child process and marks the proposal `implementation-failed` with reason
   `timeout`. No gate retries automatically — a human re-running the command after inspecting a
   failure is the retry mechanism, avoiding a retry storm against a proposal that keeps failing the
   same way.

## Consequences

- This is new capability the roadmap didn't anticipate, not an implementation of an existing
  roadmap phase — `doc/Architecture.md`'s "Designs approved, not yet implemented" row should list
  this ADR until it ships, the same way every prior phase's design was tracked there before its
  "complete" status landed.
- The eval-tournament's code-mode gate depends on `sophi-cli` being buildable into a runnable jar
  from both `main` and an arbitrary worktree inside the flow's own wall-clock budget — if the
  reactor build ever grows slow enough that two full builds plus two eval-suite subprocess runs
  don't fit comfortably inside ~20 minutes, the budget or the build scope (full reactor vs.
  affected-modules-only) will need revisiting; not a blocker for v1.
- Worktrees are not auto-deleted on success or failure, so `~/.sophi/selfmod/worktrees/` will
  accumulate until a human runs `sophi proposals cleanup <id>` — acceptable for v1's expected
  infrequent-proposal cadence, revisit if that assumption stops holding.
- Extending this same double-gate pattern to any other proposal category, or to a future scheduled
  trigger, is explicitly out of scope here — it would need its own pass through this design, not
  silently inherit it (same posture ADR-030 itself took on its own extensibility).

## References

- ADR-027 (autonomous self-improvement orchestrator) — the propose-then-confirm model, and
  `ProposalStore`/`propose_improvement` this ADR's trigger surface builds directly on top of.
- ADR-029 (evaluation, versioning & config tournament substrate) — `Tournament.evaluateAcceptance`
  and the `EvalCase`/`runSuite` harness this ADR's code-mode gate reuses without modification.
- ADR-030 (skill-write admission gate) — the static pre-write content-check precedent this ADR's
  diff-scan gate follows, and the "no `--yes` flag, every state change confirmed" discipline this
  ADR's PR-open step also follows.
- ADR-019 (invoke Claude Code tool) — `RunClaudeCodeTool`'s unconditional `DESTRUCTIVE`/`HIGH_RISK`
  classification, reused as this ADR's code-change engine and its risk-classification precedent for
  the new PR-open step.
- ADR-016 (tiered tool confirmation) — the confirmation-policy model this ADR's two separate
  human-gated steps (coding, PR-open) both route through unchanged.
- Design spec: `docs/superpowers/specs/2026-09-06-shadow-pr-self-modification-design.md` —
  gitignored/local-only, full mechanism detail behind this ADR's decisions.
