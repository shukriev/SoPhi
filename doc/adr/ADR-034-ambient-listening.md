# ADR-034: Ambient listening — passive memory and reminders from overheard speech

**Date:** 2026-09-11
**Status:** Accepted — implemented (manual end-to-end verification pending)

## Context

`sophi-companion`'s voice pipeline (ADR-028's companion tool wiring, plus the later push-to-talk
mode) is strictly turn-based: press, record, transcribe, send one chat turn. The request here was
different in kind — listen continuously while the app is open, build memory from ambient
conversation without being addressed, and create reminders/tasks from what it overhears, including
facts about people other than the user.

The first design considered a new `.sophi/skills/ambient-listening.md` skill file teaching an agent
turn how to classify overheard speech. That was rejected during design review: memory isn't written
by the agent turn at all. `SignificanceEncoder` (`sophi-memory`) is a separate, hardcoded-prompt LLM
call the agent never sees, and its existing prompt (a) defaults every memory's `provenance` to
`USER_DIRECT` and (b) instructs itself to discard "trivial exchanges (small talk, generic Q&A) —
most turns store NOTHING," which describes most of what ambient speech looks like. A skill file has
no hook into that call; the only way to get correct third-party attribution is to change what the
encoder itself sees.

A second rejected approach was energy-threshold voice-activity detection for segmenting the
continuous mic feed into clips. It fails on exactly this use case (a noise floor sits above
threshold and never closes the gate; a sentence tail below threshold gets cut mid-word) and buys
nothing, since whisper.cpp already does its own segmentation within a clip. A plain timer around
the existing `AudioRecorder` is fewer lines and more correct.

A third rejected approach — closer to the original spec's plan than the reviewed one — was routing
the reminder-detection turn through the *existing*, already memory-enabled `sophiRuntime` with no
`SessionIdContext` set (mirroring how `CheckInScheduler` avoids hanging on an unattended
confirmation). That would have worked for avoiding a hang, but it independently re-encodes the same
ambient text a second time: the turn's own prompt+reply goes through the normal `streamTurn` →
`AFTER_TURN` → `SignificanceEncoder` path regardless of what `settleExternalTurn` already recorded
for the same chunk, with the wrong (non-ambient) framing and no ambient tagging. The fix moved the
reminder-check turn onto a second, throwaway `SophiRuntime` — memory-disabled, exactly like the
existing `checkInRuntime` — instead.

## Decision

1. **An `ambient: Boolean` flag threads through the existing external-turn path**, not a new one:
   `SophiRuntime.settleExternalTurn(..., ambient)` → `HookContext(..., ambient)`
   (`sophi-extensions`) → `MemoryPlugin`'s `AFTER_TURN` hook → `TurnObservation(..., ambient)`
   (`sophi-memory`) → `SignificanceEncoder.buildPrompt`, which branches on it. All four additions
   are defaulted `false`, so every existing caller (real chat turns, `ScheduleEngine`'s task-run
   hook dispatch, every test) is unaffected. The ambient prompt variant frames the input as
   "overheard ambient conversation, not directed at you," instructs the model to prefer
   `THIRD_PARTY` provenance and `ENTITIES` room for facts about a named person, and keeps the same
   "most turns store nothing" bar.

2. **`AmbientListener`, one new pure-logic class in `sophi-companion`**: a coroutine loop records
   fixed-duration clips (default 45s) through the *existing* `AudioRecorder`/`WhisperTranscriber`
   — no VAD, no diarization — filters a small denylist of whisper's known silence-hallucination
   artifacts (`"you"`, `"thank you"`, `"[BLANK_AUDIO]"` — already called out as a known issue in
   `sophi-companion/build.gradle.kts`'s comments, never previously filtered anywhere), accumulates
   text, and periodically flushes it into chunks under the encoder's 2000-char truncation.

3. **Memory writes for every ambient chunk go through `SophiRuntime.settleExternalTurn` on the
   existing, memory-enabled runtime** — never a second one. A second memory-enabled `SophiRuntime`
   on the same home directory would hit ArcadeDB's single-open-process constraint (ADR-026) and
   silently degrade to "memory disabled."

4. **A cheap keyword pre-filter gates a second, separate, memory-disabled `SophiRuntime`** used
   only for batches that look reminder-shaped. It registers exactly one tool
   (`manage_scheduled_task`, via `schedule(tasksDir)` alone — no `builtinTools`/`skillTools`/
   `memory()`), reusing the same `tasksDir`/`TaskStore`/`RunLog` files the main runtime's scheduler
   already uses. No new reminder system; the agent just calls the existing tool when it hears
   something reminder-shaped, and everything else — creation, firing, native notification — is
   unchanged ADR-014 machinery.

5. **`BrowseFilter` gains a `provenance` filter**; the Memory tab gets a second filter-chip row.
   No new notification kind, no new review surface — once tagging is correct, browsing by
   provenance is the review UI.

6. **`VoiceController.onPttPress` now catches a failing `recorder.start()`** instead of leaving
   `turnInFlight` stuck `true` forever. This is a pre-existing latent bug that ambient listening's
   mic contention makes reachable for the first time — the fix is a root-cause fix (unhandled
   exception + stuck guard), not an ambient-specific workaround, and applies regardless of whether
   ambient listening is enabled.

## Consequences

- Mic contention between `AmbientListener` and push-to-talk is checked once per clip cycle
  (`anyVoiceInputActive()`, reading `VoiceController.state`/`SpeechOutput.isSpeaking` — no changes
  needed to either, both already expose the right `StateFlow`), not pre-empted mid-clip. A rare
  race remains where PTT is pressed while a clip is already recording; item 6 above turns that from
  "wedges forever" into "brief error, works on the next press."
- `GuiConfirmationPolicy.confirm` unconditionally fires a "Sophi needs confirmation" notification
  before checking for `SessionIdContext`, even though the ambient reminder-check runtime's lack of
  a session context means the request would be silently denied anyway. Not fixed here — the
  reminder-check runtime has no tool besides `manage_scheduled_task` registered at all, so this
  path is structurally unreachable, not merely denied.
- `sophi-cli`'s memory browse (`MemoryCommand.kt`, `SlashCommands.kt`) doesn't yet expose the new
  `provenance` filter — a natural follow-up for CLI/companion parity (ADR-028), not required here.
- No speaker diarization: attribution is content-inferred only by the encoder, and will
  misattribute when the transcript alone doesn't disambiguate who's speaking. No consent mechanism
  for third parties picked up by the mic — an explicit, deliberate scope decision, not an oversight.

## References

- ADR-013 (memory as plugin/`ContextContributor`) — `MemoryPlugin`'s `AFTER_TURN` hook is the exact
  seam the new `ambient` flag threads through; nothing about that seam's shape changed.
- ADR-026 (ArcadeDB memory storage) — the single-open-process constraint that rules out a second
  memory-enabled `SophiRuntime`, and that a test fixture in this same feature's `sophi-memory`
  change hit directly (seeding via a standalone `PalaceStore` requires closing it before a
  `JanesPalace` opens the same home directory).
- ADR-028 (shared tool wiring) — the `RuntimeBuilder`/`schedule(tasksDir)` machinery the
  memory-disabled reminder-check runtime reuses unmodified, and the CLI/companion parity gap this
  feature's `BrowseFilter.provenance` addition leaves open on the CLI side.
- ADR-014 (scheduled & goal-based tasks) — `manage_scheduled_task`/`ScheduleTaskTool`, reused
  as-is as the entire reminder mechanism.
- ADR-016 (tiered tool confirmation) — `manage_scheduled_task`'s `RiskLevel.SAFE` classification
  for a plain create (no `tool_grants`), which is what lets the reminder-check turn run without a
  confirmation prompt on the default `DENY_ALL` policy.
- Design spec and implementation plan:
  `docs/superpowers/specs/2026-09-10-ambient-listening-design.md` and
  `docs/superpowers/plans/2026-09-10-ambient-listening.md` — gitignored/local-only.
