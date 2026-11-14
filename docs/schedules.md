# Schedules: work that runs while you're away

[← Back to the README](../README.md)

Ask SoPhi in plain words ("every weekday at 9, check whether the client moved any invites") and it creates a
scheduled task with its `manage_scheduled_task` tool. Creating one that gets unattended powers (tool grants, a
page watch) asks you first.

## Kinds of task

| Kind | What it does |
|---|---|
| **Recurring** | Runs a prompt on an interval, at a time, or on a cron expression (`0 9 * * *`). |
| **Goal** | Repeats until a condition is met: judged by the model, or a shell command that passes. Capped at `max_iterations`. |
| **Watch** | Fetches a page each run and tells you only when its text changed. Times and dates are masked, so a clock on the page isn't a change. |

## Quiet unless it matters

A scheduled run that has nothing to tell you replies `NOTHING_TO_REPORT`. It is logged but never notified or
put in the briefing. A goal run can be quiet the same way, unless it ran out of iterations: an unfinished goal
always tells you.

## Safe while you sleep

- **Changes wait for you.** A run that wants to change something in another system leaves a plan for you to
  approve instead of doing it (`sophi plans`).
- **Only what you granted.** Tools that would ask you in a chat are refused in an unattended run unless the
  task was granted them.
- **The right account.** A task can be limited to the MCP servers chosen when it was made (`github-work`, not
  `github-personal`). If one of them is gone or signed out, the run fails and names it.
- **Repeated failures pause it.** Three runs failing the same way pause the task and tell you once.

## Running the scheduler

```bash
sophi schedule daemon               # keep a scheduler running in this terminal
sophi schedule install-launchd      # macOS: run due tasks on an interval in the background
sophi schedule run-due              # run what's due once, then exit (for cron)
sophi schedule list | log | pause <id> | resume <id> | remove <id>
sophi briefing                      # what ran since your last briefing
```

In a session, `/schedule` does the same. Tasks live in `~/.sophi/schedule`.
