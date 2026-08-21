# TODO Tasks

- [ ] **Implement `sophi compare-models`** (Spike S-1 follow-up)
  Plan: `docs/superpowers/plans/2026-08-20-compare-models-command.md`
  No longer blocked — the code-review pass (Ponytail lens, opus) landed and all 5 findings were
  fixed and pushed (ForgetEngine.restore()'s supersededBy gap, ScheduleEngine.runTask()'s missing
  collectContext() wiring, SkillInvocationStore's newline consistency, MemoryCommand.kt's shared
  confirmPrompt(), ToolRegistry.worstRiskAmong() dedup). Ready to start.
