# ADR-037: Ambient listening — memory correctness

**Date:** 2026-09-19
**Status:** Accepted — implemented

## Context

ADR-036 built the audit surface for ambient listening (stage 1 of four). Using it exposes the next
question: not *can you see what it stored*, but *is what it stored true*. Three defects made
overheard speech land as assertions about the user's life, and a fourth made the encoder judge that
speech without enough context to judge it well.

Two of the four were found only by reading the write path while building stage 1's review surface —
neither was in the original stage-2 scope.

## Decision

1. **The flush window is timed from when the buffer started filling, not from the last flush**
   (`AmbientListener.shouldFlush`). The last-flush stamp only advanced inside `flush()`, which only
   runs on a non-empty buffer, so it froze for the whole duration of any silence. A conversation
   starting more than one interval after the previous one therefore satisfied the interval on its
   very first clip and flushed a lone 45-second fragment — no surrounding context for the encoder,
   and its own reminder-check turn — at exactly the moment context matters most. The window now
   restarts with each new accumulation. `shouldFlush` stamps the start itself on the first call
   that sees a non-empty buffer, which keeps `accumulate` a pure text call with no clock.

2. **Unstated provenance resolves per-turn, and `VerdictMemory.provenance` became nullable.**
   `MemoryWriter` defaulted unparseable provenance to `USER_DIRECT`, and `VerdictMemory.provenance`
   itself defaulted to the string `"USER_DIRECT"` — so a model that simply omitted the field on an
   ambient turn produced a memory attributed to the user. That is the worst available default for
   overheard speech: `USER_DIRECT` is also the one value that opens ADR-035's commitment gate.
   Making the field nullable keeps "the model didn't say" distinguishable from "the model said
   USER_DIRECT"; the unstated and unparseable cases now resolve to `THIRD_PARTY` on ambient turns
   and `USER_DIRECT` on chat turns. An *explicit* `USER_DIRECT` on an ambient turn is still
   honoured — the prompt asks the model to use it only when the user is clearly the speaker, so it
   is a judgment rather than a default.

3. **Ambient turns write no profile evidence at all.** `MemoryWriter` ran
   `profile.observeEvidence(...)` unconditionally. The profile is the *user's* stable traits by the
   encoder prompt's own definition, and an ambient turn cannot establish who was speaking — so a
   guest in the room saying "I'm vegetarian" was written as a fact about the user. Gated in code,
   exactly like ADR-035's `isCommitment` rule and for the same reason: attribution is not
   something to leave to a prompt.

4. **The ambient encoder prompt names broadcast media and says to store nothing from it.** A
   microphone cannot distinguish a person in the room from a television, so film dialogue, news
   and sports broadcasts, podcasts, song lyrics, advertisements and lectures were all eligible to
   be filed as events in the user's life. Prompt-only: the encoder is already an LLM judging
   content, so this needs no new call and no new code path. The same block now also tells the model
   to answer `THIRD_PARTY` rather than guess `USER_DIRECT` when it cannot tell who is speaking.

Decisions 2 and 3 are code-level; 4 is prompt-level. The split follows the principle already
written at `MemoryWriter`'s commitment gate — what *must* hold is enforced in code, what requires
judgment is asked of the model.

## Consequences

- Ambient listening can no longer contribute to the user profile, in any form. If profile evidence
  from overheard speech is ever wanted, it needs speaker identification first, not a relaxed gate.
- Ambient commitments now require the encoder to explicitly assert `USER_DIRECT`. Silence on the
  field no longer produces one, so some genuine overheard commitments will be missed. That is the
  intended direction of the trade: a missed reminder costs less than a fabricated obligation.
- Broadcast rejection is a prompt instruction, so it is best-effort. A model that misjudges a
  podcast as conversation will still store it — stage 1's review surface is the backstop.
- Two `AmbientListenerTest` cases asserted the old flush timing. They encoded the bug as intended
  behaviour and were rewritten; the long-silence case they missed is now covered directly.
- Conversation openings are now encoded with a full window of context, which also removes the
  spurious reminder-check turn each lone fragment used to fire.

## References

- ADR-036 (ambient trust & control) — stage 1; its review surface is what makes these defects
  observable, and the backstop when the broadcast rule misjudges.
- ADR-034 (ambient listening) — the original feature; `AmbientListener`'s flush timing and the
  ambient encoder prompt both date from it.
- ADR-035 (commitment tracking) — the `isCommitment` gate whose precedent decisions 2 and 3 follow,
  and which decision 2 stops overheard speech from opening.
- Stages 3 (capture fidelity) and 4 (cost & latency) remain open; see ADR-036's references for
  the specific findings recorded against them.
