# ADR-029: Evaluation, versioning & config tournament substrate

**Date:** 2026-08-26
**Status:** Accepted — implemented

## Context

The local self-improvement roadmap (`docs/superpowers/specs/2026-08-18-autonomous-self-improvement-roadmap.md`)
had Phase 0 (`sophi-store`, PRs #54-55), Phase 0.5 (semantic lesson recall), and Phase 1 (the
orchestrator, PR #56, ADR-027) shipped. Phase 1.5 — versioning plus an eval harness — was next,
gating Phase 2 (skill-authoring admission gating) and Phase 3 (bringing `Consolidator.compress`'s
already-live, ungated memory mutation under control).

Two mutation paths already ran in production, unversioned: `WriteSkillTool` overwrote
`~/.sophi/skills/<id>.md` in place with only a namespace-regex guard, and `Consolidator.compress`
autonomously merged/decayed memory at session end with no confirmation of any kind.

During this session, an external, generically-written spec (not written with knowledge of this
codebase) was reviewed against actual source and found mostly redundant with what's already
shipped — except for two ideas with no local equivalent: keying lessons by failure-mode signature
instead of only topic/scope, and a config/prompt tournament mechanism with versioned lineage. Both
are folded into this phase's scope by explicit decision, not silently absorbed — this expanded
Phase 1.5 well past its original charter into building the tournament capability itself, not just
its supporting substrate.

**A pre-implementation review caught the first design draft's headline motivation for skill
versioning was false**, in the same way an earlier review on a different feature caught two call
sites that were assumed to already propagate a session id but didn't: skill versioning, revert, and
append-only history already shipped (`SkillVersionStore`, `sophi skill versions/revert`) before any
line of this phase's code was written — one of the original four exit criteria already passed on
`main`, untouched. `SkillVersion`'s own `trial` field was already documented as "set by a future
capability phase" — this phase was already anticipated architecturally.

## Decision

1. **`sophi-versioning` is a new leaf module**, depending only on `sophi-store` — the same
   disqualification-of-`sophi-infra` reasoning as Phase 0 (Spring Security + Micrometer,
   `sophi-core` never imports it). It provides one generic `Version`/`VersionStore` primitive
   (artifact type, artifact id, content, explicit `parentVersionId`, `producedBy`) covering four
   artifact kinds: skills, lessons, configs, and agent definitions. Every operation opens its
   ArcadeDB instance, does its work, and closes it immediately — never held open across calls or
   for a process's lifetime, unlike `JanesPalace`'s open-at-construction pattern. This matters
   because CLI, companion, and the scheduler daemon are separate processes that could each want
   this database open at once; short-lived opens let them serialize instead of permanently locking
   each other out. `sophi-skills` gains its first-ever Sophi-internal dependency (previously zero)
   by depending on `sophi-versioning`; since neither it nor `sophi-store` depends on `sophi-core`,
   this doesn't violate the documented rule that `sophi-skills` has no dependency on `sophi-core`.

2. **Skill versioning was migrated onto the new substrate, not built from scratch.** `SkillVersionStore`'s
   existing public API stayed exactly the same (`history`/`get`/`all`/`record`), so `SkillReviewCommand`
   and `computeSkillAttribution` needed zero changes; its backing storage moved from a private JSONL
   file to `VersionStore`, with a `legacyJsonlPath` constructor parameter that transparently migrates
   old history on first read. `WriteSkillTool` gained the one genuinely-missing piece: a pre-write
   baseline snapshot for a file that predates the tool or was hand-edited outside it. The old
   skill-specific `sophi skill versions`/`sophi skill revert` commands were retired in favor of a
   single `sophi versions list|show|revert <type> <id>` working across all four artifact kinds.

3. **Lessons gained a failure-mode signature and per-mutation versioning, not a rebuild.**
   `SessionEvaluator`'s existing reflection LLM call gained one more output field
   (`failureModeSignature`) alongside the `Lesson` it already produces — no new LLM pass.
   `LessonStore.add`/`archive`/`bumpUse` each record a `Version` snapshot when a `VersionStore` is
   provided (nullable, backward-compatible parameter). Agent definitions — explicitly assigned to
   this phase by the parent roadmap but dropped in the first design draft — are versioned by
   `AgentDefinition.name` (the frontmatter field, not the filename) on every load, since no
   dedicated write path exists for them; this lives in `sophi-sdk`, not `sophi-core`, since
   `sophi-core` is deliberately kept dependency-minimal (only `sophi-ai` today).

4. **The eval harness closes a real gap: `runEvalScenario` had no way to inject what a config
   change would mutate.** It ran a bare agent with no system prompt at all. `runEvalScenario` gained
   an optional `systemPrompt` parameter; `EvalCase` (hand-authored YAML files under category
   subdirectories, checked into the repo — never only in a database) and a suite runner
   (`runSuite`) aggregate results into a `Scorecard`, deliberately **not** stored as a `Version` —
   a measurement result doesn't fit `ArtifactType`/`ProducedBy`'s lineage semantics, so it's a
   separate, simpler store (`ScorecardStore`) in the same module. A case failing its first attempt
   is re-run up to three times total; disagreement across those runs quarantines it out of the
   headline score.

5. **`HarnessConfig` needed three new seams to control anything beyond system prompt/temperature/
   max tokens, and two of them exposed hardcoded machinery this phase didn't expect to touch.**
   `RuntimeBuilder.configVersion(id, versionStore)` applies whichever fields it can reach directly.
   Critic on/off required a fix in **two independent hardcode sites**, not one: `buildPlanRunner`
   and `ScheduleEngine` each separately construct their own `LlmStepCritic` instance, so
   `PlanRunnerConfig.criticEnabled` had to be wired at both, falling back to a new
   `StepCritic.ALWAYS_FULL_CONFIDENCE` no-op. Top-k skill injection caps `SkillTool`'s previously
   unconditional full-list description. Tool-description overrides use Kotlin interface delegation
   (`Tool by delegate`) to substitute a description without touching the `Tool` SPI, applied last in
   `RuntimeBuilder.build()` so it reaches whichever tool ends up registered under a name regardless
   of registration order.

6. **The tournament mechanism is genuinely new capability, not a retrofit — treated with the same
   caution ADR-024 gave ToT widened replan.** `proposeMutation` conditions on real signal (lesson
   failure-mode signatures with no addressing lesson, and `ToolStatsStore.stats()` — not
   `ToolReliabilitySection`'s already-filtered prompt renderer). `evaluateAcceptance` is pure math:
   a challenger is accepted only if its improvement exceeds the incumbent's own run-to-run noise
   floor (stddev) with no category regressing beyond a cap; a jump exceeding 10% is flagged for
   mandatory manual review regardless of statistical significance, since the suite/grader itself is
   part of what can be gamed. Promotion is human-confirmed — no `--yes` flag exists.
   `SOPHI_TOURNAMENT_ENABLED` fails toward OFF (the same direction as `SOPHI_ORCHESTRATOR_ENABLED`,
   the inverse of `SOPHI_TOT_SEARCH_ENABLED`) — the mechanism refuses to run at all unless
   explicitly enabled. Skill/lesson/agent-definition/config versioning itself has no separate kill
   switch, since recording history has no autonomous behavior to gate.

## Consequences

- **Auto-harvesting eval cases from session history was dropped from this phase entirely.** No
  reliable "same goal" data source exists (`SessionOutcome` has no goal/prompt field), and the
  orchestrator's structurally propose-only design (ADR-027: `toolGrants = emptySet()`, hardcoded
  `ConfirmationPolicy.DENY_ALL`, SAFE tools only) means it couldn't run as an orchestrator tool even
  if the data existed. Manual case authoring covers this phase's own exit criteria; named as a
  future item once a real goal-tracking field exists.
- **`Consolidator.compress`'s retrofit remains Phase 3's job**, using the versioning mechanism this
  phase built — this phase does not touch memory mutation.
- **`WriteSkillTool` admission-gating (candidate detection, sandboxed verification, human approval
  before a write happens) remains Phase 2's job.** This phase only makes writes recorded and
  revertible after the fact, not approved before they happen.
- The determinism exit criterion had to be restated in case-count terms (raised the minimum suite
  size and "no more than one case flips") rather than a raw percentage, since a binary pass/fail
  grader can't reach below-5%-headline-score granularity at fewer than 20 cases — a real arithmetic
  constraint the first design draft missed.
- `sophi-web`'s tool wiring still doesn't go through this substrate — out of scope, matching
  ADR-028's own noted gap.

## References

- ADR-027 (autonomous self-improvement orchestrator) — the propose-only model this phase's
  tournament trigger design follows (CLI-triggered, human-confirmed, orchestrator may only suggest).
- ADR-026 (ArcadeDB memory storage) — the single-process-lock constraint `sophi-versioning`'s
  open-write-close pattern exists specifically to route around.
- ADR-024 (ToT widened replan) — the probation-discipline precedent (ship labeled
  "accepted — probation," visible counters, kill switch) this phase's tournament mechanism follows.
- ADR-028 (shared tool wiring) — `RuntimeBuilder` as the shared assembly point this phase's
  `configVersion()`/`builtinTools()`/`skillTools()` integration builds on.
- Design spec and implementation plan: `docs/superpowers/specs/2026-08-26-phase-1.5-eval-versioning-tournament-design.md`
  and `docs/superpowers/plans/2026-08-26-phase-1.5-eval-versioning-tournament.md` — gitignored/local-only.
