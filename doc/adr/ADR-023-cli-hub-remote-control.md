# ADR-023: CLI session monitoring & remote control via a companion-owned hub

**Date:** 2026-08-10
**Status:** Accepted — implemented

## Context

`sophi-cli` and `sophi-companion` already share `~/.sophi/sessions` (`FileSessionManager`), so
a CLI session's transcript already surfaces in the companion once a turn finishes — but
`AgentLoop.turn()` saves exactly once, at turn end, and each `sophi` invocation is its own OS
process with no channel for the companion to see a turn in progress or act on it.

## Decision

1. **New `sophi-hub` module**, not a home in `sophi-core` or `sophi-cli` — shared protocol
   (`HubEvent`/`HubCommand`), server, and client in one Maven module, installed to
   `mavenLocal()` for `sophi-companion` the same way `sophi-sdk` already is. Rejected:
   splitting server/client across `sophi-companion`/`sophi-cli` directly (no shared build
   system between them today); putting it in `sophi-core` (no reason for every embedder to
   gain a Ktor dependency).
2. **Companion owns the hub's lifecycle; CLI registration is on by default, best-effort.**
   `CompanionRuntime` starts/stops `HubServer` with its own lifecycle. `sophi-cli` attempts one
   connection on startup, unconditionally; unreachable is silent, not an error — a CLI session
   must behave identically with or without a companion running. Rejected: an opt-in flag
   (adds a step to remember for no safety benefit, since failure is already silent); a
   standalone `sophi hub` daemon (no consumer but the companion itself).
3. **Protocol reuses `TurnEvent` and `ConfirmationPolicy` shapes; no new `AgentHook` points.**
   `HubEvent`'s per-token/tool-call cases mirror `sophi-core`'s existing `TurnEvent`; the CLI
   forwards them via a second `onEvent` consumer alongside the one already driving terminal
   rendering. Rejected: routing through `sophi-extensions`'s `PluginRegistry`/`AgentHook` —
   too coarse (turn/tool boundaries only, no per-token granularity) and would add hook points
   to an interface every `SophiPlugin` implements for a concern specific to this one consumer.
4. **Confirmation: race, first response wins, no lock.** `RemoteAwareConfirmationPolicy` races
   the terminal's y/N answer against a remote answer over the hub; whichever resolves first
   wins, the loser's coroutine is cancelled. Rejected: an explicit hand-off/lock — needs a
   session-ownership state machine (who holds it, stale-claim timeouts, what happens if the
   companion quits mid-session) to solve a problem that, at "one person, occasionally two
   windows" scale, a race resolves correctly almost all the time.
5. **Companion port-bind conflicts degrade safely, not by falling back to hub-client mode.**
   If a second companion instance can't bind the hub port (a previous instance still running),
   `HubServer.start()`'s failure is caught and logged; that instance simply has no remote CLI
   monitoring rather than becoming a client of the other instance's hub — the latter would
   duplicate this feature's plumbing a second time just for a stale-instance edge case.

## Consequences

- A running CLI session's live status (idle/running/awaiting confirmation) and confirmation
  prompts are visible in `sophi-companion`'s existing Sessions/Chat tabs and confirmation
  banner — no new tab, the distinction is only which transport `sendMessage`/
  `respondToConfirmation` use underneath (`RemoteSessionRegistry`, keyed by session id).
- The companion can send a message into a running CLI session (delivered as if typed at the
  next idle prompt, buffered if a turn is in flight) and answer its confirmation prompts.
- `HubServer` binds `127.0.0.1` only; no auth layer, same trust boundary as `sophi-web`.
- Fixed a pre-existing lost-update race in `CompanionRuntime.awaitConfirmation`/
  `respondToConfirmation`: `pendingConfirmationSessionIds.value = pendingConfirmationSessionIds.value + x`
  is a non-atomic read-modify-write, and two sessions racing to request confirmation
  concurrently could silently drop one session's id. The extra background coroutines this
  design adds per `CompanionRuntime` instance made the race reproduce consistently; fixed with
  `MutableStateFlow.update { }` instead.
- Streamed token content for a remote CLI session is not yet accumulated into the companion's
  chat transcript (`sessionMessages`) — only status (idle/running/needs confirmation) is live;
  the transcript still updates once the session's file is flushed to disk at turn end. Wiring
  live token accumulation for remote sessions is a follow-up, not done in this pass.
- Out of scope for v1: cross-machine hub access, explicit session locking, hub/registry state
  surviving a companion restart, multiple simultaneous hubs, and `sophi-schedule`/`sophi-web`
  registering with the hub (both run unattended — no human on the other end of a confirmation
  race).

## References

- ADR-005 (JSONL tree sessions) — the on-disk format both surfaces already share.
- ADR-016 (tiered tool confirmation) — the `ConfirmationPolicy` seam this design races against.
- ADR-022 (sophi-companion) — the process this design gives a second job: hub host.
- Spec: `docs/superpowers/specs/2026-08-10-cli-session-monitoring-design.md`.
- Plan: `docs/superpowers/plans/2026-08-10-cli-session-monitoring.md`.
