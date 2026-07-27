# ADR-015: Native OS calendar integration

**Date:** 2026-07-26
**Status:** Accepted

## Context

Sophi can act on its own timer (ADR-014's `sophi-schedule`) but has no way to see or
manage the user's actual calendar — real appointments created in Calendar.app, Outlook,
or a Linux desktop's calendar app, by the user or by other people. Users want Sophi to
create, read, update, and delete calendar events, including recurring ones, against
whatever the OS actually shows as "the calendar."

## Decision

1. **Full native read access, not a Sophi-owned store.** `CalendarProvider` is the seam;
   tools read/write the OS's actual calendar rather than a private JSONL log (like
   `TaskStore`) exported one-way via `.ics`. A private log can't answer "what's on my
   calendar today" for events Sophi didn't create.
2. **Six single-purpose tools, not one bundled `manage_calendar_event` tool.**
   `Tool.riskLevel` is a per-tool-instance property that gates confirmation in
   `AgentLoop` — splitting `create`/`list`/`get` (`SAFE`) from `update`/`delete`
   (`DESTRUCTIVE`) lets `ConfirmationPolicy` actually gate the two side-effecting
   operations, which `ScheduleTaskTool`'s own bundled action-dispatch design cannot
   express (it's `SAFE` overall, including for its `remove` action).
3. **Structured recurrence, not raw RRULE, in the tool schema.**
   `Recurrence(frequency, interval, count|until, byWeekday)` translates to an RRULE
   string for macOS/Linux (AppleScript's Calendar.app and libical-backed EDS both speak
   it) and to Outlook COM's discrete `RecurrencePattern` properties for Windows, which
   has no RRULE concept at all.
4. **Multi-calendar support via optional `calendar_id`, plus `list_calendars`.** Most
   native calendars aren't single-calendar (Home/Work in Calendar.app, folders in
   Outlook) — every tool takes an optional `calendar_id` defaulting to the OS default.
5. **No attendees/invites in v1.** The one field where backends diverge sharply, and the
   only feature that would make Sophi trigger a real external side effect (emailing
   real people) rather than just modifying the user's own calendar state.
6. **Interface designed for three platforms; only macOS implemented.**
   `CalendarProvider` and the `CalendarEvent`/`Recurrence` model are OS-agnostic;
   `MacCalendarProvider` (`osascript` against Calendar.app, same shell-out pattern as
   `MacNotifier`) is the only implementation. Windows (Outlook COM via PowerShell) and
   Linux (evolution-data-server via PyGObject) get `UnsupportedCalendarProvider`
   (clear "no calendar backend available" error) until built.
7. **New module, not folded into `sophi-core`.** `sophi-calendar` depends on
   `sophi-core` only — no LLM calls needed anywhere in its tool-execution path, unlike
   `sophi-schedule`'s goal mode. Wired into `sophi-cli` for v1; web/sdk deferred, same
   treatment as the `MemoryPlugin` precedent.

## Reasons

1. **Read access has to be real to be useful.** The whole point of "native OS calendar"
   is seeing what's actually there, including events Sophi never created.
2. **Per-action risk gating matters more here than for internal scheduling.** Sophi's
   scheduled tasks are internal state; calendar events are the user's real
   appointments. `AllowlistConfirmationPolicy`/`DENY_DESTRUCTIVE` (ADR-014) already
   exist — this decision makes them actually effective for calendar writes instead of
   vacuously true.
3. **Reuse the existing shell-out pattern rather than adding heavy dependencies.**
   `MacNotifier` already proves shelling out to native scripting works well enough;
   Outlook COM and EDS get the same treatment on their platforms rather than a
   JNA/COM4J/D-Bus Java client dependency.
4. **Ship the platform we can prove, write the rest down.** Same discipline as the
   reasoning-visibility work (article-20): ship the narrower thing that's actually
   verifiable in this environment, record the fork explicitly rather than guessing at
   Windows/Linux behavior with no way to manually check it.

## Consequences

- Six tools registered in `sophi-cli` only for v1: `create_calendar_event`,
  `list_calendar_events`, `get_calendar_event`, `list_calendars` (`SAFE`);
  `update_calendar_event`, `delete_calendar_event` (`DESTRUCTIVE`).
- Windows/Linux calendar backends tracked as a deferred follow-up in `TODO_TASK.md`,
  same treatment as "Non-macOS notifiers" already there.
- `sophi-web`/`sophi-sdk` wiring deferred, same treatment as `MemoryPlugin`'s item #1
  in `TODO_TASK.md`.
- Attendees/invites deferred entirely — no tool-surface field for them in v1.
- Reading an event's recurrence back as a structured `Recurrence` is also deferred —
  `get`/`list` always return `recurrence = null`; only `create`/`update` write it.
- First `sophi-core`-adjacent module with zero LLM dependency anywhere in its
  tool-execution path.

## References

- Spec: `docs/superpowers/specs/2026-07-26-native-calendar-integration-design.md`
- Plan: `docs/superpowers/plans/2026-07-26-native-calendar-integration.md`
- ADR-006 (tool interface) — `riskLevel`-gated confirmation reused here.
- ADR-014 (scheduled & goal tasks) — `Notifier`'s per-OS-with-fallback pattern is the
  direct precedent for `CalendarProvider`/`UnsupportedCalendarProvider`.
