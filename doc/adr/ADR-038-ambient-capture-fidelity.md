# ADR-038: Ambient listening — capture fidelity

**Date:** 2026-09-19
**Status:** Accepted — implemented

## Context

ADR-036 made ambient listening auditable and ADR-037 made what it stored true. Both operate on
whatever text reached the encoder. This ADR is about the stage before that: how much of what was
actually said in the room reaches the encoder at all, and how much of what reaches it is whisper
inventing content for silence.

All three defects below date from ADR-034's original implementation, and all three are in
`sophi-companion`. There is no engine-visible change, so this ADR has no CHANGELOG entry —
recording it here keeps the ambient decision trail in one place, as ADR-034 already did for
companion internals.

## Decision

1. **Transcription moved off the record loop, behind a `Channel`.** The loop recorded a clip and
   then ran whisper inline — spawning a process and loading a ~148 MB model — with the microphone
   closed for the whole of it. Every clip cost a gap of however long transcription took, and
   anything said during that gap was never recorded. Clips now go to a `Channel<Path>` consumed by
   a single coroutine that gates, transcribes, accumulates and flushes; the recorder restarts
   immediately after `stop()`, so the only remaining gap is `stop()`/`start()`.

   *One* consumer, not a coroutine per clip: the listener's buffer is mutable state and transcripts
   must stay in clip order. A single consumer gives both without a lock.

   Capacity is 2 with a suspending send. Whisper finishes well inside `clipMs` in normal operation
   so the channel never fills; under sustained overload the record loop blocks on `send` and the
   microphone gap returns. That is the honest degradation — preferable to dropping audio silently
   or letting the queue grow without bound — and it is marked with a `ponytail:` comment naming the
   ceiling.

2. **The silence gate measures the loudest 100 ms window, not the whole-clip mean**
   (`rmsAmplitude` → `peakWindowRms`). An ambient clip is mostly silence with a sentence somewhere
   in it, and a whole-clip mean divides that sentence's energy across the entire recording: 0.2 s
   of quiet speech in a 10 s clip averages to ~0.0028, under the 0.005 gate, so the clip was
   discarded as an empty room and the sentence never transcribed. The loudest window sees it at
   ~0.014.

   Peak-of-windows is always ≥ the whole-clip mean, so this change can only ever send *more* audio
   to whisper — it cannot newly drop speech that previously got through. What it costs is some of
   the gate's saving on silent clips. `silenceRms` stays a parameter and is the knob for winning
   that back; it was left at 0.005 rather than re-tuned, because any new value would be a guess
   without real room recordings to calibrate against.

3. **The hallucination filter recognises whisper's looped-sentence failure.** The denylist was four
   exact strings compared after trimming `.!?`, so `"Thanks for watching!"` with surrounding
   whitespace, `"[Music]"`, `"(upbeat music)"` and `"Subtitles by the Amara.org community"` all got
   through. Worse, whisper's most common output for near-silence is not a known phrase at all but
   one arbitrary sentence repeated — which no phrase list can catch. The filter now normalises
   properly (lowercase, non-alphanumerics to spaces, collapsed runs), matches a wider artifact set
   by exact/prefix/suffix, and discards any clip that is three or more identical sentences.

   Artifact phrases are matched against the **whole transcript**, never a sentence inside it. That
   is what makes common words like "you" and "music" safe to list: a 45-second clip whose entire
   content is the word "you" is an artifact with near-certainty, while the same word mid-sentence
   is ordinary speech.

## Consequences

- The microphone is now open essentially continuously. Sustained overload is the only case that
  reintroduces a gap, and it is bounded by the channel's capacity rather than by transcription
  time.
- The silence gate passes more clips to whisper than before, so ambient listening's per-hour cost
  rises somewhat on quiet rooms. This was accepted deliberately: dropping real speech is the worse
  failure, and the change is provably non-regressive in that direction.
- Three identical sentences is a heuristic. A person who genuinely repeats themselves three times
  verbatim in one clip will have that clip discarded. Judged far rarer than whisper looping.
- `ggml-base.en` is unchanged. Far-field room audio is materially harder than the close-mic
  push-to-talk path the model was chosen for, and `small.en` would roughly triple the model
  download against a bundle recently cut from 306 MB to 190 MB. Deferred deliberately, not
  overlooked — stage 1's review surface now makes it possible to judge whether accuracy is in fact
  the binding constraint before paying that cost.
- Stage 4 (cost & latency) remains open: the `REMINDER_KEYWORDS` prefilter still fires an LLM turn
  on most flushes, and per-clip model reload is still the real cost driver — `whisper-stream` needs
  SDL2 and a new release artifact in `VoiceInstaller`.

## References

- ADR-034 (ambient listening) — the original record loop, silence gate and denylist, all three
  revised here.
- ADR-036 (ambient trust & control) — stage 1; its review surface is what makes capture quality
  observable, and the backstop when the hallucination filter misses.
- ADR-037 (ambient memory correctness) — stage 2; operates on the text this stage delivers.
