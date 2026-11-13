# Extending SoPhi: skills, plugins, MCP, plan-first

[← Back to the README](../README.md)


**Skills** (`sophi-skills`) are plain Markdown files with YAML frontmatter —
drop them in a directory and load them with `SkillLoader().load(dir)`:

```markdown
---
title: "Deploy runbook"
description: "How to deploy this service safely"
tags: [ops, deploy]
---

Steps to deploy...
```

**Plugins** (`sophi-extensions`) hook into the turn lifecycle
(`BEFORE_TURN` / `AFTER_TURN` / `BEFORE_TOOL` / `AFTER_TOOL` / `ON_ERROR`).
`sophi-infra` ships one ready to use:

- `MetricsPlugin(meterRegistry)` — emits Micrometer counters for turns started/completed/errored

A dollar budget for a session is `RuntimeBuilder.budget(maxUsd)`, not a plugin (see **Models and cost** in [cli.md](cli.md)).

Tool permissioning is no longer a plugin — `AgentLoop`'s `riskLevel`/`ConfirmationPolicy`/`grants` mechanism (ADR-016) is the one gate; see `RuntimeBuilder.grants(names)` in [sdk.md](sdk.md) for the SDK-embedding equivalent of the old allowlist.

Register plugins either manually (`RuntimeBuilder.plugin(...)` /
`PluginRegistry.register(...)`) or via JVM `ServiceLoader` discovery
(`PluginRegistry().discover()`).

### Secrets and signing in to MCP servers

Keys for MCP servers live in the macOS Keychain, not in `mcp.json`. Store one with `sophi secret set cloudflare` (a hidden prompt, or one line on stdin; `sophi secret list` and `sophi secret remove <name>` too), then reference it as `${secret:cloudflare}` in a stdio server's `env` or a remote server's new `headers`:

```json
{"servers":[
  {"name":"cf","transport":"stdio","command":["npx","cf-mcp"],"env":{"CLOUDFLARE_API_TOKEN":"${secret:cloudflare}"}},
  {"name":"internal","transport":"http","url":"https://mcp.example.com/mcp","headers":{"Authorization":"Bearer ${secret:internal}"}}
]}
```

Remote servers that sign in with OAuth (Sentry, Linear, Notion, ...) need no key: `sophi mcp login sentry`, or `/mcp login sentry` in a session, opens the sign-in page; Sophi registers itself, stores the sign-in in the Keychain and renews it silently, also when the CLI and the companion share one sign-in. When a renewal is refused, the server's tools answer "needs you to sign in again" and nothing opens a browser on its own. A server without self-registration (GitHub) takes an `"oauth": {"clientId": "...", "clientSecret": "${secret:gh}", "scopes": ["repo"]}` block. `sophi mcp logout <server>` forgets it.

Stored values never reach the model: they're masked as `[REDACTED:secret]` in every MCP result and error (values under 8 characters are stored but not masked). A missing `${secret:...}` fails only that server, naming the secret. SDK: `RuntimeBuilder.credentials()` (or `credentials(store)` for your own `CredentialStore`), `SophiRuntime.mcpLogin(config, openBrowser)` / `mcpLogout(name)`.

### Plan first: approve changes before they happen

When a task wants to change something in another system (an MCP tool that isn't read-only), Sophi doesn't do it and doesn't stop to ask either. It records the change and carries on: lookups still run, so the plan has real ids and names. At the end of the turn you see one plan:

```
offboard jane@acme.com: 3 changes in 3 systems
Looked up (read-only, already ran):
  okta find_user → 00u9f… (read-only per server)
Changes waiting for you:
  1 okta deactivate_user id=00u9f…
  2 github remove_org_member org=acme user=jdoe (allowed by your rule)
  3 slack deactivate_user user=U04…
[a] approve all  [v] view arguments  [d 1,2] drop steps  [t 18:00] run at a time  [s] save for later  [n] cancel
```

Nothing changes before you approve, even calls a permission rule or grant would allow. Approving replays exactly those calls (same tools, same arguments) with no model involved, stopping at the first failure; each call is in the audit log with its plan and step. A plan with one change shows the usual allow prompt instead. `t` approves it now and runs it later from the scheduler; a recurring scheduled task that wants to change something leaves a pending plan and tells you instead.

`sophi plans` lists plans; `sophi plans show|approve|run|cancel <id>` (or `/plans ...` in a session). Plans live in `~/.sophi/plans`. Settings (`/settings`): `plan-first` = `outside` (default) / `every` (local changes too) / `off`, and `trust-read-only-hints` (default on: a tool its MCP server marks read-only runs without asking in these turns; list lookups in `safeTools` to rely on your own word instead). Scheduled runs use the servers in `~/.sophi/mcp.json`. SDK: `RuntimeBuilder.planFirst(mode)`, `TurnEvent.PlanReady`, `SophiRuntime.approvePlan` / `runPlan` / `cancelPlan` / `schedulePlan` / `planFirstScope`.

### Large MCP servers (deferred tools)

An MCP server that lists more than 10 tools is loaded on demand: the model sees only their names and calls `tool_search` to load the ones it needs for the turn, so the schemas stay out of the prompt. Change the limit with `"deferToolsOver"` at the top of `.sophi/mcp.json` (SDK: `RuntimeBuilder.deferToolsOver(n)` when no mcp.json is loaded).

### Browsing websites (browser-automation MCP + self-authored site skills)

Sophi has no built-in browser — it connects to any browser-automation MCP server you
configure in `.sophi/mcp.json`, the same way it connects to any other MCP server (see
`docs/examples/mcp-browser-server.json` for the shape). Once configured:

- Mark that server's **read-only** tools (navigate, screenshot, page snapshot / get
  text, list tabs) as `safeTools` — everything else (click, type, select, submit)
  stays `DESTRUCTIVE` and requires confirmation on every call, including in goal mode.
- Install `docs/skills/browsing-sites.md` into `~/.sophi/skills/` once. It teaches
  Sophi to recall a site it's seen before (via a skill it wrote itself last time,
  through the `write_skill` tool) instead of re-exploring, and to record what it
  learns after each visit.
- Every `write_skill`/`install_skill` call is gated: static content checks (secret
  scan, size cap, and for `install_skill`'s third-party content, a prompt-injection
  scan) run before anything lands, and the confirmation prompt shows a real diff or
  source summary instead of raw JSON. A new write lands as a `trial` version;
  `sophi skill verify <id>|--all` runs it through the eval suite and reports a
  human-confirmed promote/revert recommendation.
- **Trust boundary:** marking `navigate`/`screenshot` as safe means Sophi can navigate
  to any URL — including `file://` paths or internal-network addresses — without
  confirmation, since `safeTools` matches by tool name only, not by argument. If that's
  not an acceptable default for your environment, leave `navigate` out of `safeTools`.

