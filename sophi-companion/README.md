# 🖥️ sophi-companion

An OS tray / menu-bar desktop app for SoPhi, built with Compose Multiplatform
Desktop. It embeds `sophi-sdk` **in-process** — no HTTP hop through `sophi-web` —
and is the first real consumer of the SDK as an embeddable library (see
[ADR-022](../doc/adr/ADR-022-sophi-companion.md)).

Click the tray icon to open the window: a left sidebar lists every session (local and
any `sophi-cli` sessions registered via the embedded hub) with a live status dot —
Idle (gray), Running (green), Needs confirmation (orange), Error (red) — sorted so
anything needing attention floats to the top, then by most recently active. Below the
session list, fixed nav items open **MCP** (configured servers — enable/disable, remove),
**Goals** (scheduled tasks — create, run now), and **Skills** (installed skills — add
from a local path or git URL, remove). Selecting a session opens its chat in the main
panel. A **Settings** tab holds the speech-to-text/text-to-speech toggles and
`workspaceDir` (see [Configuration](#configuration) and [Tools](#tools) below).

Local sessions stream live, token by token — same as CLI sessions.

Multiple sessions run concurrently — each turn gets its own coroutine and its own
`StateFlow`, so sending a message in one session never blocks the UI or another
session.

## Remote CLI sessions

The companion embeds a local hub (`sophi-hub`, ADR-023) automatically — no setup. Any `sophi`
CLI session started while the companion is running registers with it and appears in the
Sessions tab with a live status badge (`Idle`/`Running`/`Needs confirmation`), tagged `(CLI)`.
Opening it in the Chat tab lets you send it a message or answer its confirmation prompts —
whichever side answers first wins, there's no hand-off/lock to manage. The hub binds
`127.0.0.1` only. Configure the port via `hubPort` in `~/.sophi/companion.json` (default
`8765`) if it collides with something else on your machine — pass the same value to `sophi-cli`
via `--hub-port`.

> **Not part of the Maven reactor.** `sophi-companion` is a standalone Gradle
> project (Compose Desktop is Gradle-only). It resolves `dev.sophi:sophi-sdk`
> from `mavenLocal()`, which is why the `mvn install` step below is mandatory
> rather than optional.

## Requirements

- JDK 17+ (verified on JDK 25)
- Maven, to install the reactor into `~/.m2`
- Gradle is **not** required — use the bundled wrapper (Gradle 9.3.1)

## Running it

```bash
mvn install -DskipTests     # from the repo root — publishes sophi-sdk to mavenLocal()
cd sophi-companion
./gradlew run
```

Re-run `mvn install -DskipTests` after any change to `sophi-core`, `sophi-ai`,
`sophi-mcp`, `sophi-hub`, `sophi-schedule`, or `sophi-sdk` — the Gradle build reads the
installed jar, not your working tree.

## Configuration

On first launch the app shows a setup screen — pick **Claude** or **Local /
OpenAI-compatible**, fill in the fields, and it writes `~/.sophi/companion.json`.
The same screen reappears if the saved file is ever unusable, pre-filled and
explaining what was wrong, so a broken config can be repaired in-app.

| Field | Meaning |
|---|---|
| `providerType` | `claude` or `openai-compat` |
| `model` | Model id — e.g. `claude-sonnet-4-5`, or `qwen3:8b` for Ollama |
| `baseUrl` | Required for `openai-compat`; ignored for `claude` |
| `apiKey` | Optional. `null` + `claude` falls back to `ANTHROPIC_API_KEY` |
| `contextWindowTokens` | Your model's real context window (see below) |
| `maxTokens` | Max tokens generated per response |
| `sessionsDir` / `mcpConfigPath` | Default to the `sophi-cli` locations |
| `hubPort` | Port the embedded hub listens on for CLI sessions to register with (default `8765`) |
| `workspaceDir` | Root directory the `bash`/`write_file`/`edit_file` tools are confined to (default `~/.sophi/workspace`) — see [Tools](#tools) below |

**Claude:**

```json
{
  "providerType": "claude",
  "model": "claude-sonnet-4-5",
  "contextWindowTokens": 200000,
  "maxTokens": 4096
}
```

**Local model via Ollama** (vLLM is the same with `http://localhost:8000/v1`):

```json
{
  "providerType": "openai-compat",
  "model": "qwen3:8b",
  "baseUrl": "http://localhost:11434/v1",
  "apiKey": null,
  "contextWindowTokens": 32768,
  "maxTokens": 8192
}
```

Omitted keys fall back to defaults, so a partial file is valid.

> **Set `contextWindowTokens` to your model's real window.** It defaults to
> `200000`, a Claude-sized number. Sophi compacts a turn's earlier tool rounds at
> 80% of this value, so leaving the default on a 32k local model means compaction
> never fires and the model overflows its context instead. Check with
> `ollama show <model>`.

`ANTHROPIC_API_KEY` is only consulted for `providerType: "claude"` — a local
Ollama/vLLM server won't receive an Anthropic key as its bearer token just because
the variable is exported. Set `apiKey` explicitly if your vLLM server is behind auth.

Sessions and MCP servers are shared with `sophi-cli` by default, so sessions you
started in the terminal show up in the Sessions tab.

Settings are read once at startup — editing the file requires a restart.

## Tools

The companion registers the same tool surface `sophi-cli` does, via `sophi-sdk`'s
`RuntimeBuilder` ([ADR-028](../doc/adr/ADR-028-shared-tool-wiring.md)):

- **Builtin file/bash/fetch/search tools** (`read_file`, `write_file`, `edit_file`, `grep`,
  `glob`, `bash`, `fetch_url`, `web_search`, `get_current_date_time`) — scoped to the `workspaceDir`
  setting above (default `~/.sophi/workspace`), not the process's working directory. Companion runs
  unattended scheduled/goal-mode turns with nobody watching, so this stays sandboxed by default;
  point `workspaceDir` at a real projects folder for `sophi-cli`-equivalent reach.
- **Calendar** (`create_calendar_event`, `list_calendar_events`, `get_calendar_event`,
  `update_calendar_event`, `delete_calendar_event`, `list_calendars`) — macOS only.
- **Subagent delegation** (`delegate_to_subagent`) — delegates to whatever
  `AgentDefinition` `.md` files are under `agentsDir` (default `~/.sophi/agents`, same as
  `sophi-cli`). Each chat tab's turn carries its own session id via `SessionIdContext`, so
  concurrent delegations from two different chat tabs attribute correctly to their own session,
  not each other's.
- **Goal decomposition** (`decompose_goal`) — same `SessionIdContext`-based attribution as
  subagent delegation.
- **Skill invocation** (`skill`, `install_skill`, `write_skill`) — lets the *agent itself* find and
  follow an installed skill, or write a new one, mid-conversation. This is a different thing from
  the **Skills tab** in the sidebar, which is a human-facing UI for installing/removing skills
  from a local path or git URL; the tools above are what the model can call, the tab is what you
  click.
- **Scheduled tasks** (`manage_scheduled_task`) — lets the agent create/list/update scheduled or
  goal-mode tasks from chat, in addition to the Goals tab's own create button; both read/write the
  same `~/.sophi/companion/tasks.json`.

Every tool above whose risk tier is above `SAFE` (e.g. `bash`, `write_file`) goes through the same
per-session Approve/Deny confirmation UI described in [Known limitations](#known-limitations).

## 📦 Building the installer

```bash
cd sophi-companion
./gradlew packageDistributionForCurrentOS
```

Output lands in `build/compose/binaries/main/`:

| Platform | Format | Path |
|---|---|---|
| macOS | `.dmg` | `dmg/SophiCompanion-1.0.0.dmg` (~200 MB) |
| Linux | `.deb` / AppImage | `deb/` , `app/` |
| Windows | `.msi` | `msi/` |

The unpacked `SophiCompanion.app` is ~287 MB — a full JVM runtime image ships
inside the bundle. Bundle ID is `dev.sophi.companion`; icons are generated from
`doc/images/logo.png`.

To build one specific format directly: `./gradlew packageDmg` (or `packageDeb`,
`packageMsi`).

**Packaging is not optional on macOS.** A bare `java -jar` process generally
can't register with Notification Center, so native notifications only work
reliably from a properly bundled `.app`.

### Verified

On macOS (the dev machine): `packageDistributionForCurrentOS` succeeds,
`hdiutil verify` reports a valid checksum, the image mounts with the standard
drag-to-`/Applications` layout, and the packaged `.app` launches directly (not
via `./gradlew run`) and stays alive as a standalone process with the icon
embedded in `Contents/Resources`.

### Not verified

The `.deb`, AppImage, and `.msi` targets share the same `nativeDistributions`
block but have **never been built or run on Linux or Windows**. Treat them as
configured, not tested.

## ⚠️ Build gotchas

Two environment issues you will hit, both already fixed in this module's build
files — documented here because the versions that made them necessary will move.

**1. Homebrew's OpenJDK fails Compose Desktop's jpackage vendor check.** The
failure reads like a broken JDK rather than a conservative safety check. Fixed
in `gradle.properties`:

```
compose.desktop.packaging.checkJdkVendor=false
```

**2. Gradle 9.x flags an undeclared dependency between the per-format packaging
tasks and `:packageAppImage`.** Compose Multiplatform 1.9.0's plugin doesn't
declare it, and Gradle 9 treats an undeclared read of another task's output as an
error:

```
> Task :packageDmg FAILED
  Reason: Task ':packageDmg' uses this output of task ':packageAppImage' without
  declaring an explicit or implicit dependency.
```

The fix is an explicit `dependsOn` — but it **must** be registered lazily. The
obvious eager form compiles, runs, and silently wires nothing, because Compose
registers `packageDmg`/`packageDeb`/`packageMsi` *after* the build script is
evaluated, so every lookup returns `null` and the safe-call swallows it:

```kotlin
// WRONG — silently does nothing, build stays green until someone packages from clean
listOf("packageDmg", "packageMsi", "packageDeb").forEach { name ->
    tasks.findByName(name)?.dependsOn("packageAppImage")
}

// RIGHT — applies to tasks registered later
tasks.matching { it.name in packageFormatTasks }.configureEach {
    dependsOn("packageAppImage")
}
```

## Known limitations

- **Tool confirmation is a real per-session Approve/Deny UI**, not a stub.
  `GuiConfirmationPolicy` fires a native notification, then routes the actual
  confirmation via `SessionIdContext` into that turn's Chat tab as a
  `SessionState.NeedsConfirmation` card — the same risk-tier (`RiskLevel`) gating
  `sophi-cli` uses. A scheduled/goal-mode run with nobody watching the app still
  blocks on a DESTRUCTIVE-tier call until someone opens the tab and answers it;
  there is no auto-approve or timeout. See [ADR-028](../doc/adr/ADR-028-shared-tool-wiring.md).
- **Concurrent same-named tool calls can show the wrong result in the live Chat view.**
  `TurnEvent.ToolCallStarted`/`ToolCallFinished` (sophi-core) carry a tool name but no call id,
  so when two calls to the *same* tool are in flight at once, their finish events are matched to
  the oldest still-unfinished call of that name (FIFO), not necessarily the one that actually
  produced that result. Different-named concurrent calls are unaffected. A session reloaded from
  disk does not have this problem — its persisted `toolCallId` gives an exact match.
- **Tray click behavior differs by OS.** On macOS, left-clicking an AWT
  `SystemTray` icon opens its context menu instead of firing `onAction` — use
  "Open Sophi" from the menu. Linux and Windows are expected to fire `onAction`
  on click.
- **No automated UI tests.** Every non-UI class has a Kotest suite
  (`./gradlew test`); the Compose UI is verified manually.
- No auto-update, no state syncing between installed copies.

## See also

- [ADR-022](../doc/adr/ADR-022-sophi-companion.md) — design decisions and rationale
- [ADR-028](../doc/adr/ADR-028-shared-tool-wiring.md) — the shared `RuntimeBuilder` tool wiring
  described in [Tools](#tools) above, and why calendar/skill-tools ended up where they did
- `doc/articles/article-25.md` — the write-up of what embedding `sophi-sdk` exposed
- `doc/articles/article-29.md` — the `SessionIdContext` propagation gap ADR-028 fixed
- [Architecture](../doc/Architecture.md) — where this module sits
