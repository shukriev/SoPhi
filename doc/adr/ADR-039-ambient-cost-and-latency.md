# ADR-039: Ambient listening — cost & latency

**Date:** 2026-09-19
**Status:** Partially implemented — the reminder gate is done; persistent whisper is blocked on a
release artifact (see Blocked below)

## Context

Stage 4 of the ambient work (ADR-036 trust, ADR-037 correctness, ADR-038 capture). Two costs
dominate ambient listening: a second LLM turn fired on most flushes by a keyword prefilter, and a
~148 MB whisper model reloaded from disk for every clip.

## Decision

**Reminders are gated on the encoder's verdict, not on keyword matching.**

`REMINDER_KEYWORDS` contained `"i'll"`, `"i will"`, `"i need to"`, `"i have to"`, `"i should"`,
`"tomorrow"` and `"later today"`. Those fire on ordinary conversation — "I'll see you tomorrow" is
a goodbye, not a reminder — so the memory-disabled reminder runtime burned a full agent turn on
most flushes. The list was simultaneously too narrow: a reminder phrased without one of its words
("the passport application is due before the trip") was never noticed at all.

The encoder already decides exactly this question, on a call we pay for regardless. Its
`commitment` field means "the user states they will do something for someone else or themselves — a
promise or obligation", and after ADR-037 an ambient memory only reaches `isCommitment` when the
encoder explicitly placed the *user* as the speaker. Sampling `openCommitments()` before and after
the batch and looking for a new id reads that verdict for free — the same before/after diff pattern
ADR-036 used for `TaskStore`.

This improves precision and recall at once, and removes an LLM turn from the common path.

Two consequences of reading that particular surface, both judged correct:

- `openCommitments()` only reports PERSONAL-or-below sensitivity, so a commitment the encoder marked
  SENSITIVE raises no reminder. For something that auto-creates a scheduled task from overheard
  speech, that is the right side to err on.
- `REMINDER_KEYWORDS` survives as a fallback. `Main.kt` gates memory on `activeProfile.memoryEnabled`
  independently of `settings.ambientListeningEnabled`, so ambient listening can run with memory off
  — and with no palace there is no verdict to read. Deleting the list would have silently killed
  ambient reminders in that configuration.

**Flush latency is deliberately not addressed.** A reminder overheard just after a flush waits up to
the full interval, so "remind me in two minutes" is stale on arrival. Every fix — a shorter
interval, an early flush on reminder-shaped text — costs more encoder turns, directly against this
stage's purpose, and an early flush would also reintroduce the context-free fragments ADR-037 just
removed. Ambient capture is not the right mechanism for short timers; asking Sophi directly is.

## Blocked: persistent whisper

Every clip spawns a `whisper-cli` process that loads `ggml-base.en.bin` (~148 MB) from disk,
transcribes, and exits. Keeping the model resident is the real fix, and ADR-038 deferred it. The
intent for this stage was to do it properly. It is blocked on an artifact that does not exist:

- `voice-tools-manifest.json` at release `voice-tools-v1` declares exactly four artifacts —
  `whisper-cli-macos-{arm64,x64}` and `piper-runtime-macos-{arm64,x64}`. The installed whisper
  directory contains a single 3.3 MB `whisper-cli`.
- `whisper-stream` is a separate whisper.cpp example requiring SDL2 for audio capture. It is not
  built, not packaged, and not referenced anywhere in `VoiceInstaller`.

Unblocking it requires, in order: building `whisper-stream` against SDL2 from whisper.cpp ref
`b4938` for macOS arm64 **and** x64; publishing both tarballs to the `voice-tools-v1` release;
regenerating the pinned manifest with new SHA-256 sums. Only then can the installer entry, a
streaming transcriber, and the capture-loop changes be written against a known binary — its flags,
its stdout framing and its segmentation behaviour all shape that code, and guessing them would
produce something untestable and probably wrong.

`scripts/build-whisper-stream.sh` does the build-and-package half. It exists because the naive
build is not shippable: `whisper-stream` links Homebrew's SDL2 by absolute path, so the binary
would run only on the machine that built it. The script bundles SDL2 beside the binary, rewrites
the load path to `@loader_path`, re-signs ad-hoc (`install_name_tool` invalidates the signature,
and Apple Silicon kills a modified unsigned binary at launch), and refuses to package anything that
still references `/opt/homebrew` or `/usr/local`.

Two things surfaced only by running it, both of which would have shipped broken artifacts:

- **`GGML_NATIVE` must be OFF.** Left at its default, ggml targets the build host's CPU. Cross-
  building x64 from Apple Silicon it hands `-mcpu=apple-m2` to the x86_64 compiler, which rejects
  it outright — loud, at least. Building arm64 natively it silently bakes in `-mcpu=apple-m2`, so
  the published binary can fault with an illegal instruction on an M1 or any chip that isn't the
  one that built it. A binary published for other people's machines must target the baseline.
- **SDL2 has to be built from source for x86_64.** Homebrew publishes no x86_64 SDL2 bottle for
  current macOS, and its `sdl2` formula is now an alias for `sdl2-compat`, an SDL3 shim. The script
  falls back to building SDL2 (pinned at `release-2.32.10`) for any architecture Homebrew cannot
  supply a matching slice for, so `--arch both` works from a single Apple Silicon machine.

Both artifacts are built and verified: fresh extraction, correct slice, the bundled SDL2 matching
it, `otool -L` showing no build-host paths, and the binary launching. The x64 one was run under
Rosetta far enough to enumerate capture devices and open the microphone at 16 kHz mono, failing
only on the missing model argument — which is what proves the signature and `@loader_path` rewrite
hold, not just that the file links.

**CI is the intended path, not the local script.** `.github/workflows/voice-tools-release.yml`
already builds the other voice artifacts on native runners — `macos-14` for arm64, `macos-15-intel`
for x64 — so a `build-whisper-stream` job was added to it alongside. Native runners mean no
cross-compilation at all, which removes the hazard that produced the `-mcpu=apple-m2` failure
above; `GGML_NATIVE=OFF` stays regardless, because a runner's CPU is no more the user's CPU than
this laptop's is.

That job calls the script rather than inlining the SDL2 handling, so the bundling, `@loader_path`
rewrite and re-signing have one implementation. `--no-manifest` exists for it: each runner builds
one architecture, and the existing `publish` job writes a single manifest covering the whole set.
The local script's manifest-and-publish half remains useful only for one-off local builds.

The `publish` job runs `gh release create` with a tag supplied at dispatch, so it makes a *new*
release rather than mutating `voice-tools-v1`. `VoiceInstaller` is bumped to `voice-tools-v2`
accordingly, with the tag reduced to a single constant that `RELEASE_BASE_URL`, `MANIFEST_URL` and
the test's expected URLs all derive from — previously the tag was written out three times.

**Ordering matters here.** That bump is inert but not harmless: every install reads the manifest at
that URL at install time, so until `voice-tools-v2` is dispatched and published, a fresh install
on the companion's branch resolves a URL that 404s. The release must be published before the
companion change ships.

Installing the new binary is a separate step still to come: `VoiceInstaller` downloads
`whisper-cli-macos-$arch` and `piper-runtime-macos-$arch` by name and does not yet fetch
`whisper-stream`. Adding it is deliberately deferred until there is a streaming transcriber to use
it — shipping an extra binary that nothing calls is just bundle weight.

**Nothing has been published.** Uploading binaries to the public release is the owner's call; the
locally built tarballs are in `.whisper-stream-build/dist/`.

Note that ADR-038 already removed this cost's effect on *capture*: transcription runs off the record
loop, so a slow model load no longer closes the microphone. What remains is CPU and battery, not
lost audio — which is why deferring it further is tolerable.

## Consequences

- The reminder runtime now runs rarely rather than on most flushes. With memory enabled, the
  keyword list no longer influences behaviour at all.
- Ambient reminders now inherit the encoder's speaker judgment, so a guest's commitment creates no
  task. This follows from ADR-037 rather than being decided here.
- `startAmbientListening` gained an `openCommitmentIds` seam, matching the existing
  `nowMs`/`recorder`/`transcriber` parameters, so both branches of the gate are testable without a
  memory-enabled runtime.
- Per-clip model reload remains. It costs CPU and battery on every clip, including silent ones that
  pass ADR-038's gate.

## References

- ADR-036 (trust & control) — the `TaskStore` before/after diff pattern reused here.
- ADR-037 (memory correctness) — `isCommitment` only reaching ambient memories the encoder
  attributed to the user is what makes this gate trustworthy.
- ADR-038 (capture fidelity) — moved transcription off the record loop, which is why the remaining
  whisper cost is CPU rather than lost audio.
- ADR-034 (ambient listening) — `REMINDER_KEYWORDS` and the reminder runtime originate here.
