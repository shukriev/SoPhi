# ADR-040: Ambient listening — streaming capture

**Date:** 2026-09-20
**Status:** Accepted — implemented (manual end-to-end verification with live speech pending)

## Context

ADR-039 left one thing unfinished, blocked on an artifact rather than a decision: every ambient
clip spawned a `whisper-cli` process that loaded `ggml-base.en.bin` (~148 MB) from disk,
transcribed, and exited. `voice-tools-v2` now publishes `whisper-stream` for both architectures, so
the model can stay resident.

## Decision

**Capture is one long-lived `whisper-stream` process.** `WhisperStreamSource` spawns it and exposes
its utterances as a `Flow<String>`; collecting the flow holds the microphone, cancelling it
releases the device. The model loads once per session, and the binary's own voice-activity
detection decides what counts as speech.

**`--step 0` is mandatory, not a tuning choice.** It selects VAD mode, the only mode whose output
survives a pipe. At any other step size `whisper-stream` runs a sliding window and repaints partial
hypotheses using terminal escape codes (`\33[2K\r`). VAD mode instead frames each utterance:

```
### Transcription 3 START | t0 = 1200 ms | t1 = 4300 ms

[00:00:00.000 --> 00:00:03.100]   call the dentist tomorrow

### Transcription 3 END
```

`StreamTranscriptParser` consumes that framing. Segments always carry a timestamp prefix — unlike
`whisper-cli`, `stream` has **no** `--no-timestamps` flag, so stripping it is not optional. Anything
arriving outside a block is dropped: it is either the `[Start speaking]` banner or, if the process
were ever launched without `--step 0`, sliding-window repaints that must not reach memory.
Diagnostics go to stderr and transcription to stdout, so no log filtering is needed.

Both facts were confirmed by running the published binary with the exact flags the code passes: it
reports `using VAD, will transcribe on speech activity` and `timestamps = 1`.

**Push-to-talk outranks ambient for the microphone.** Only one process can own the device, so the
capture flow is cancelled when push-to-talk or speech output becomes active and reacquired
afterwards, polled on a short interval (250 ms) that bounds how long PTT waits. The cost is a model
reload per PTT press — but on a user-initiated action, rather than every 45 seconds as before.
Leaving the process running and discarding its output was rejected: ambient would transcribe
Sophi's own text-to-speech into memory.

**The flush deadline is checked on a ticker, not on arrival.** `shouldFlush` used to be polled every
clip cycle. With capture event-driven, checking it only when an utterance arrives would leave one
sentence followed by silence buffered until the *next* utterance — hours, in a quiet room. A ticker
emitting blanks is merged into the same collector, so there is still exactly one consumer and the
listener needs no lock.

**`whisper-stream` is fetched only when ambient listening is enabled.** `isInstalled()` keeps its
existing meaning and `isStreamInstalled()` is separate. Adding it to the standard set would have
reported every existing installation as incomplete and re-downloaded the ~112 MB piper runtime for
people who never enable ambient.

## Consequences

- **`AudioLevel`/`peakWindowRms` is deleted** — ADR-038 replaced its whole-clip mean with a
  peak-of-windows statistic, and this ADR removes its last caller, since `whisper-stream` does its
  own VAD. The clip recorder and the `Channel` pipeline also go for ambient; `AudioRecorder` and
  `WhisperTranscriber` remain, still used by push-to-talk. Net −255 lines.
- ADR-038's async-transcription work was not wasted but is largely superseded: it was the right fix
  for a clip architecture, and this replaces the architecture. The hallucination filter and the
  batching/flush logic in `AmbientListener` both carry over unchanged and are still load-bearing —
  whisper hallucinates in VAD mode too.
- `vad_thold` (whisper's `-vth`, default 0.6) is now the calibration knob that `silenceRms` used to
  be. No single value fits every room or microphone; it is a constructor parameter.
- A capture process that dies is reported through the existing `AmbientState.Failed` path, with the
  tail of its stderr as the reason rather than a bare exit code.
- Enabling ambient listening on an installation predating `voice-tools-v2` fails with an explicit
  manifest error instead of starting a loop that can only fail.
- **Not yet verified with live speech.** The parser is tested against the exact framing from
  `stream.cpp`, and the binary was confirmed to start in VAD mode with these flags, but no one has
  spoken into it end to end and watched a memory land. That needs a person and a microphone.

## References

- ADR-039 (cost & latency) — recorded this as blocked; `scripts/build-whisper-stream.sh` and the
  `build-whisper-stream` CI job produced the artifact that unblocked it.
- ADR-038 (capture fidelity) — the clip-loop fixes this supersedes, and the hallucination filter it
  keeps.
- ADR-036 (trust & control) — the review surface, which remains the backstop for whatever the VAD
  and the hallucination filter let through.
