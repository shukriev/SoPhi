# ADR-030: Skill-write admission gate

**Date:** 2026-08-27
**Status:** Accepted — implemented

## Context

Phase 1.5 (ADR-029) built the versioning and eval-harness substrate this phase needed but
explicitly left `WriteSkillTool` admission-gating — candidate detection, sandboxed verification,
human approval before a write happens — as still Phase 2's job. Phase 2's own roadmap entry
(`docs/superpowers/specs/2026-08-18-autonomous-self-improvement-roadmap.md`) assumed skills were
still CLI-only and that `WriteSkillTool` already wrote autonomously; both claims were checked
against the actual source before any code shipped and found stale: `RuntimeBuilder.skillTools()`
is already called by both `CliRuntime.kt` and `sophi-companion/Main.kt` (ADR-028's shared tool
wiring), and every `write_skill`/`install_skill` call already goes through ordinary
`DESTRUCTIVE`-tier confirmation — a human already clicks yes/no on every write today. What was
missing was real content review: the confirmation prompt rendered raw escaped JSON (an unreadable
markdown blob), `InstallSkillTool` never touched the versioning substrate at all (unlike
`WriteSkillTool`), and `SkillVersion.trial` — set on every write since ADR-029 — had no operational
meaning: no probation timer, no promotion path, no revert trigger.

## Decision

1. **Static content checks run inline, before either tool writes anything.** `checkSkillContent`
   (secret/credential pattern scan, size cap) and `checkInstalledSkillContent` (that superset plus
   a prompt-injection-phrase scan, for third-party content) block a write entirely on failure — no
   file lands, no version is recorded. `SkillInstaller.install()` gained an additive `validate`
   callback parameter (default no-op) so `InstallSkillTool` can reject a discovered skill before it
   is ever copied into the target directory, without duplicating clone/discovery logic.
2. **A passing write lands immediately as a trial version; verification is a separate, later step.**
   No eval suite runs inside `write_skill`/`install_skill`'s own `execute()` — matching
   `SkillVersion.trial`'s original design intent and ADR-024's probation precedent, and avoiding a
   multi-minute delay inside a single tool call. `InstallSkillTool` now records a baseline-and-trial
   `SkillVersion` per installed skill, closing the one real gap Phase 1.5 didn't (it only migrated
   `WriteSkillTool` onto the substrate).
3. **`sophi skill verify <id>|--all` runs the eval suite twice — once against the previous version
   (or its absence), once against the current trial content — and recommends promote, revert, or
   manual review.** No new `ScheduleEngine` task type: the command is designed for the user's own
   cron/launchd, the same "OS-scheduler-first" precedent ADR-014 already established, rather than
   building bespoke in-process scheduling for what is fundamentally "run a CLI command
   periodically." The acceptance rule (`recommendFromScores`, `sophi-sdk/SkillVerification.kt`)
   deliberately does **not** reuse `Tournament.evaluateAcceptance` as-is — a config swap is global
   and unconditional so it must prove an improvement, but a skill only affects behavior when the
   model chooses to invoke it, so requiring an aggregate score improvement would reject perfectly
   good skills that don't move the suite's needle. Both functions share one extracted
   `regressedCategories` helper for the regression math itself.
4. **Coverage is checked, not assumed.** A private `InvocationTrackingSkillTool` (the same `Tool by
   delegate` pattern `RuntimeBuilder`'s `DescriptionOverrideTool` already uses) wraps the candidate
   run's `SkillTool`, counting real `skill(name=<id>)` calls. A verification result whose eval suite
   never actually exercised the skill under test surfaces a `coverageWarning` alongside the
   recommendation — the result can confirm the skill's presence didn't break anything else, but
   cannot speak to the skill's own quality.
5. **Every state change — promote or revert — requires explicit human confirmation.** `sophi skill
   verify` only reports; a separate confirmed step performs the mutation. Promoting a skill out of
   trial is implemented the same way `Tournament.kt`'s `activateConfigVersion` promotes a config:
   record a new `SkillVersion` with identical content and `trial = false`, keeping
   `sophi-versioning`'s append-only design intact rather than rewriting history in place. No
   `--yes` flag exists, matching ADR-027's and ADR-029's own propose-then-confirm discipline.
6. **Confirmation prompts can show a tool-supplied preview instead of raw JSON.** One additive
   method, `Tool.confirmationPreview(argumentsJson): String? = null`, resolved once — at the exact
   point `AgentLoop` already resolves the real `Tool` instance for `riskLevel()` — and carried on
   `ConfirmationRequest.preview` rather than threading a `ToolRegistry` into the CLI/companion
   confirmation policies (`GuiConfirmationPolicy` never rendered raw JSON at all; the actual
   companion rendering site turned out to be `ChatTab.kt`, not the policy class the original design
   draft assumed). `WriteSkillTool` renders a line-set diff against the current version;
   `InstallSkillTool` states its source and target directory, honest that exact skill ids aren't
   knowable before the source is cloned. No kill switch — every state-changing action already
   requires confirmation, so there is no unattended-mutation path this phase introduces.

## Consequences

- `ProducedBy` still doesn't distinguish `write_skill`-authored versions from `install_skill`-authored
  ones (`SkillVersionStore.record` maps both to `WRITE_SKILL_TOOL` when `trial = true`) —
  `verifySkill`'s retroactive check always applies the stricter `checkInstalledSkillContent` superset
  to both, a deliberate simplification rather than adding a new enum value for a distinction nothing
  else needs yet.
- Eval-suite categories may not exercise the specific skill being verified at all, in which case
  both baseline and candidate runs score identically and `verifySkill` would otherwise recommend
  `PROMOTE` with no real signal behind it — this is why `coverageWarning` exists, mirroring Phase
  1.5's own accepted limitation (hand-authored eval cases, no auto-harvesting) rather than
  overclaiming verification coverage the suite doesn't have.
- Extending this same gate to any tool beyond `write_skill`/`install_skill` (a hypothetical future
  agent-definition-authoring tool, for instance) is out of scope — it would need its own pass
  through this design, not silently inherit it.
- `sophi-web`'s tool wiring still doesn't route through any of this, matching the same
  already-noted gap from ADR-028 and ADR-029.

## References

- ADR-014 (scheduled goal tasks) — the OS-scheduler-first precedent this phase's "no new
  `ScheduleEngine` mode" decision follows for `sophi skill verify --all`.
- ADR-021 (website browsing self-authored site skills) — `WriteSkillTool`'s original design and the
  `site-*` namespace guard this phase's static checks and preview sit on top of, unchanged.
- ADR-024 (ToT widened replan) — the probation-discipline precedent this phase's async
  write-then-verify flow follows.
- ADR-027 (autonomous self-improvement orchestrator) — the propose-only, human-confirmed model this
  phase's promote/revert flow mirrors.
- ADR-028 (shared tool wiring) — confirms `RuntimeBuilder.skillTools()` already reaches every host,
  correcting this phase's own roadmap entry's stale "CLI-only" premise.
- ADR-029 (evaluation, versioning & config tournament substrate) — the `sophi-versioning` substrate,
  eval harness, and `Tournament.kt` acceptance-math precedent this phase builds directly on top of.
- Design spec and implementation plan:
  `docs/superpowers/specs/2026-08-26-phase-2-skill-write-admission-gate-design.md` and
  `docs/superpowers/plans/2026-08-26-phase-2-skill-write-admission-gate.md` — gitignored/local-only.
