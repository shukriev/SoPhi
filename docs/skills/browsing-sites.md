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

## 1. Recall

Derive `site-<hostname>` from the task's target URL: lowercase the host, replace every
`.` with `-` (e.g. `github.com` -> `site-github-com`, `docs.example.com` ->
`site-docs-example-com`). Check the list of available skills already in your context
(this tool's own description lists every skill id) for a match.

If found, load it with `skill(name="site-<hostname>")` before doing anything else.

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

## 4. Act

Perform the actual task using the interaction tools (click, type, select, submit).
Each of these requires confirmation on every call — that's expected, not a bug to work
around.

## 5. Record

After finishing this step (or as soon as you've learned something materially new),
call `write_skill` to create or update the site's skill:

- `id`: `site-<hostname>` (must match `site-[a-z0-9-]+` or the tool will refuse it)
- `title`: the site's name
- `description`: one line — what this skill covers
- `tags`: `["site"]`
- `body`: entry URL(s), the workflow as numbered steps, selectors/text anchors that
  worked, gotchas hit, a last-updated note

**Never write credentials, session tokens, or secrets into a skill body.** Login is
handled by an already-authenticated browser profile or a separate interactive login
step — it is never something to record here.

## 6. Self-heal

If a documented step fails at runtime (the site changed), that's a signal to
re-explore that specific part and update the skill via `write_skill` again — not to
retry the same failing action repeatedly, and not to leave the stale documentation in
place for next time.
