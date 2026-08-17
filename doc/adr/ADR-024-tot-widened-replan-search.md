# ADR-024: Tree-of-thought widened replan search

**Date:** 2026-08-17
**Status:** Accepted — probation

## Context

`PlanRunner` already performs a depth-first search with backtracking, and has since ADR-018: a
failed step anchors a replan, `Planner.replan()` regenerates one new tail from that anchor, the
tail executes, and the cycle repeats up to `maxReplans`. What was never noticed is that this
search runs at **width 1** — exactly one candidate continuation is generated, and it is executed
without ever being compared against an alternative that was never produced.

So "add tree-of-thought to Sophi" is not a new subsystem. It is widening one node of a search
that already exists, at the one point in a run that holds *evidence* — a concrete failure reason
attached to a concrete step — rather than speculation.

This ADR is written retroactively. The work shipped from a brainstorm → plan → implementation
cycle rather than an ADR-gated design, and the evidence gathered afterwards was equivocal enough
that the honest record is worth more than the usual "Accepted — implemented" stamp. Background
lives in `docs/superpowers/plans/2026-08-14-tot-widened-replan.md` and its paired spec; note
both are gitignored and local-only, as is `TODO_TASK.md` where the measurements are recorded.

## Decision

1. **ToT, not GoT — because merge is incoherent over actions.** Graph-of-Thoughts
   (arXiv:2308.09687) adds exactly one thing over tree search: *aggregation*, fusing several
   thoughts into one. Merge is well-defined when a thought is **data** and incoherent when it is
   an **action**. Merging two candidate plans is meaningful; merging two *executed* `PlanStep`s
   is fiction, because both branches already wrote to disk and selecting one output does not
   unwrite the other. Since aggregation is only meaningful at the data layer, adopting GoT would
   push the graph to the planner anyway — at which point merge is extra machinery on top of a
   search that already delivers the benefit. Rejected.

2. **Branch the planner, never the executor.** Three placements were rejected for the same
   underlying reason. *Branching executed steps*: each candidate is a full `AgentLoop.turn()`
   with tools live, so generating k and discarding k-1 leaves k-1 sets of real, unundoable side
   effects, drains the shared `RunBudget` k-fold, and multiplies work against a fixed
   `taskTimeoutMs` — and on this path `allowParallelSteps` is `true`, so those branches would
   collide concurrently. *Sibling sub-plan merge*: inherits all of that. *BFS over plan
   prefixes*: safe, but runs into credit assignment — whether step 2 was a good choice is
   usually unknowable until step 5 succeeds or fails, so per-depth scores are mostly noise.

3. **A decorator on the existing `Planner` interface, not an edit to `LlmPlanner`.**
   `TreePlanner(delegates: List<Planner>, critic: PlanCritic) : Planner`. `LlmPlanner` stays
   single-purpose and its tests stay valid. `PlanRunner` is untouched — zero diff.

4. **`plan()` delegates straight through; only `replan()` searches.** The initial plan has no
   failure evidence to score against, so branching there ranks speculation. Replan is the only
   node holding a concrete reason for a concrete step.

5. **Goal-mode scoping is structural, not configured.** `ScheduleEngine.runTask()`'s
   `TaskMode.Goal` branch is the only production construction site of a `TreePlanner`; the CLI's
   `/plan` and `DecomposeGoalTool` keep reaching a bare `LlmPlanner` through `buildPlanRunner`.
   Interactive paths therefore *cannot* reach the search — there is no flag to set wrong. This is
   deliberate: goal-mode is the unattended path, where nobody is watching to redirect a bad plan.

6. **A new `PlanCritic`, because `StepCritic` structurally cannot do this job.**
   `StepCritic.judge(step, agentOutput)` scores a *finished* step against its instruction. A
   candidate tail has no output — it has not run. `PlanCritic.score(goalPrompt, candidate,
   failureReason)` is a sibling interface following `LlmStepCritic`'s shape exactly.

7. **`PlanCritic` fails OPEN, and the degraded path is the old system.** Any timeout, provider
   error or unparseable response scores `1.0`. When every candidate fails open they all tie,
   `maxBy` returns the *first* maximum, and the result is `delegates[0]` — precisely the width-1
   behaviour that existed before. This is a quality gate, not a safety gate; `ConfirmationPolicy`
   and `RiskLevel` remain the only safety gate and are untouched.

8. **Candidate diversity is the caller's job, via a temperature ladder.**
   `LlmPlanner.completeText` hardcoded `temperature = 0.0`, so k delegates sharing one
   temperature return k *identical* tails and the search is a no-op that still costs 6 calls.
   `LlmPlanner` gained a `temperature: Double = 0.0` constructor parameter (the default preserves
   prior behaviour byte-for-byte) and `ScheduleEngine` supplies one delegate per temperature.
   `TreePlanner` has no diversity mechanism of its own, by design.

9. **`PlanCritic` gets an explicit 300s timeout, unlike `StepCritic`'s 30s default.** A local 9B
   reasoning model measured **166s** to emit a single score. At the 30s default every score fails
   open, all candidates tie, and the search silently collapses to pre-search behaviour **while
   still paying for every extra planner and critic call**. That asymmetry is the whole point:
   `StepCritic` failing open degrades to a *safe assumption*, whereas `PlanCritic` failing open
   degrades to a *no-op that costs full price*. Hosted models are nowhere near it (DeepSeek: ~3s),
   so the defect was silent and local-only — the worst combination, and worth engineering against
   rather than leaving to chance.

10. **A single-element delegate list is the documented off switch.** `delegates.size == 1`
    short-circuits to a plain delegate call: zero extra LLM calls, byte-identical to pre-search
    behaviour. `SOPHI_TOT_SEARCH_ENABLED=false` selects it at process start (decision 12), and it
    is also how the A/B's baseline arm was run.

11. **Status is "probation", not "implemented", because value was not demonstrated.** Probes
    against DeepSeek established the mechanism works: the ladder produces genuinely distinct
    candidates under real strategic ambiguity, and the critic discriminates well — spread up to
    0.8, scoring "delete the failing tests" at `0.0` and a question-begging plan with no
    diagnosis step at `0.1`. But **the baseline was never bad**: across four scenarios,
    temperature 0.0 scored 0.85–0.9 and always found the root cause, and the search changed the
    selected candidate only 1 time in 4, by 0.07 — inside the critic's own noise. The end-to-end
    A/B was underpowered and is not evidence either way (both arms floored at 0/15 and 0/5 Met,
    because `maxIterations` was too tight for the goals chosen). Deleting on that evidence would
    discard a working mechanism; stamping it "implemented" would claim a win that was not
    measured. Probation is the honest third option.

12. **A kill switch as an environment variable, not config plumbing.**
    `SOPHI_TOT_SEARCH_ENABLED` read via `System.getenv` at the `PLAN_SEARCH_TEMPERATURES` site,
    following the `BRAVE_SEARCH_API_KEY` precedent in `SophiCli.kt`. Unset or any other value
    keeps the ladder; `false` or `0` (case-insensitive) collapses it to `listOf(0.0)`. A feature
    on probation needs to be killable without a code deploy, and a probation-scoped switch does
    not justify a config file, a CLI flag, or a `PlanRunnerConfig` field — all rejected as
    outliving the experiment they exist to serve.

## Consequences

- Goal-mode replans now cost up to 3 planner + 3 critic calls each, bounded by `maxReplans`
  (3) — roughly 18 extra non-tool calls worst case per goal task. No `RunBudget` consumption:
  it counts `executeOnce` invocations only. Losing candidates are data and never execute, so
  there are no extra agent turns, tool calls, or side effects.
- The interactive path is unchanged and structurally unreachable from this search.
- `RunRecord` carries `replans: Int?` and `decompositions: Int?` per goal run, surfaced by
  `sophi schedule log`. `null` means no plan ran (a `Recurring` task); `0` means a goal run that
  genuinely never replanned — the two must stay distinguishable. Both counts appear together
  because a failed step **decomposes before it replans** (ADR-020's `canDecompose` check runs
  first), so `replans` alone cannot separate "the search ran" from "decomposition intercepted the
  failure". Without this, probation would have been undecidable.
- **The probation review**, when it happens: compare replan counts for the same goal tasks with
  the ladder enabled versus `SOPHI_TOT_SEARCH_ENABLED=false`. Tasks that merely *fail* a step
  will measure ADR-020 decomposition rather than the search; the search is reached via the
  unmet-stop-condition branch, an exhausted sub-plan, or unresolved-dependency "stuck".
- If it graduates, ADR-018 needs amending: `replan`'s contract changes from "produce the
  continuation" to "produce the best of k continuations".
- `LlmStepCritic` shares the same 30s default and will fail open on slow local models. Left
  unchanged — it is pre-existing ADR-018 behaviour and degrades safely — but it does make step
  confidence unreliable on the local-model provider.

## References

- ADR-018 (plan-and-execute) — defines `Planner`, `replan`, `StepCritic`, and the fail-open
  rationale this follows. Explicitly listed "tree-of-thought/multi-path plan search" as out of
  scope; this is that deferred item, taken up narrowly.
- ADR-020 (generalized goal decomposition) — the sub-plan tree, which is decomposition, not
  search, and whose `canDecompose` check runs *before* replanning.
- ADR-017 (auto mode hybrid risk classifier) — the `sophi-core` → `sophi-ai` precedent and the
  cheap-judge-call pattern both critics follow.
- ADR-016 (tiered tool confirmation & grants) — untouched; still the only safety gate.
- Besta et al., "Graph of Thoughts" (arXiv:2308.09687); Yao et al., "Tree of Thoughts"
  (arXiv:2305.10601).
- Plan and spec: `docs/superpowers/plans/2026-08-14-tot-widened-replan.md` and
  `docs/superpowers/specs/2026-08-14-tot-widened-replan-design.md` — both gitignored/local-only,
  as are the measurements in `TODO_TASK.md`.
