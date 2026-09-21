---
title: Browsing sites
description: Explore-then-learn protocol for browser-automation MCP tools — recall a site's known workflow before acting, or explore and record it if none exists yet.
version: 1.0.0
tags: [browsing, site-learning]
---

# Browsing sites

Use this whenever a task involves a website and a browser-automation MCP tool (e.g. a
tool name prefixed `browser__`) is available. Each step that touches a browser tool
runs this whole sequence itself — plan-mode steps are isolated from each other, so
don't assume an earlier or later step already did part of it.

**Never search for credentials.** The browser tool's session is assumed to already be
authenticated — e.g. a CDP-connected browser you're already logged into on the target
site. Never grep, read, or otherwise search local files, shell history, environment
variables, config, or other Sophi sessions/skills for a password, token, or "the
provided credentials" — none will be there, and looking is itself a privacy problem
(other sessions may contain unrelated sensitive data). If a page actually shows a login
form and you have no site skill covering it, that means the browser session is not
authenticated: stop and tell the user to log in themselves in the browser this tool is
attached to, rather than hunting for a way around it.

## 1. Recall

If a site skill already exists for the target, a pointer naming it is **already in your
context** — it names the skill id, its documented procedures, and how to load it. Load
it with `skill(name="site-<hostname>")` before doing anything else.

If no pointer appeared, the site may still have a skill that recall could not match from
the wording of the request — recall keys off the message, so "check my CRM tasks" names
no host. The available skill ids are listed in the `skill` tool's own description; check
there for a `site-` id matching the target.

Deriving an id by hand: lowercase the host, drop a leading `www.`, replace every `.`
with `-` (`github.com` -> `site-github-com`, `www.maidplus.de` -> `site-maidplus-de`,
`docs.example.com` -> `site-docs-example-com`). Dropping `www.` matters — otherwise one
site ends up with two skills that never find each other.

## 2. Decide

If the loaded skill already documents the workflow this task needs, skip straight to
**4. Act** — that's the whole point of recording it last time.

If no skill exists for this host, or it doesn't cover what's needed, continue to
**3. Explore**.

## 3. Explore

Using only read-only browser actions (navigate, screenshot, page snapshot / get text,
list tabs — whichever of those this MCP server exposes as `safeTools`), map just the
part of the site relevant to this task: the nav structure, the relevant form, the
workflow's steps. Stay scoped to what this task needs — this is not a full-site crawl.

Record what you found with `write_skill` in the default `mode=map`. A map is what the
site *is*: entry URLs, navigation, the screens and forms that exist. It is **not** a
workflow — you have not done anything yet, and writing steps you have only looked at is
the failure this protocol exists to prevent.

Anything you noticed you cannot yet do goes under `## Known unknowns`, one line each.

## 4. Act

Perform the actual task using the interaction tools (click, type, select, submit).
Each of these requires confirmation on every call — that's expected, not a bug to work
around.

## 5. Record

Having actually completed the task, call `write_skill` with `mode=procedure` and add a
single entry under `## Procedures`:

- `id`: `site-<hostname>` (must match `site-[a-z0-9-]+` or the tool will refuse it)
- `mode`: `procedure`
- `body`: the full skill — `## Map`, `## Procedures` with your new `### <task name>`
  entry, `## Known unknowns` with anything resolved struck out, and `## Last updated`

A procedure entry is: numbered steps, the selectors or text anchors that actually
worked, and any gotcha you hit. Write only the task you just performed. One write
records one completed task; the tool rejects a second entry, and a task you merely
watched belongs under Known unknowns, not here.

**Never write credentials, session tokens, or secrets into a skill body.** Login is
handled by an already-authenticated browser profile or a separate interactive login
step — it is never something to record here.

## 6. Deepen

When you are working on a site whose skill has a `## Known unknowns` list and you have
room to, you may resolve entries yourself rather than waiting to be asked: navigate,
inspect, and carry out the steps needed to find out how something works, then record it
with `mode=procedure` exactly as in step 5.

Two things bound this. Every interaction still goes through the confirmation layer —
that gate decides what may happen unattended, and it is not yours to route around.
And a procedure still means *you performed these steps*: if you worked out how
something probably works without doing it, that is a Known unknown with better notes,
not a procedure.

## 7. Self-heal

If a documented step fails at runtime (the site changed), that's a signal to
re-explore that specific part and update the skill via `write_skill` again — not to
retry the same failing action repeatedly, and not to leave the stale documentation in
place for next time. Correcting a procedure you have just re-performed is a
`mode=procedure` write; if you could not complete it, downgrade it to a Known unknown
rather than leaving steps that do not work.
