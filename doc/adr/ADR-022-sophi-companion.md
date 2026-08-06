# ADR-022: sophi-companion — an OS tray app embedding sophi-sdk in-process

**Date:** 2026-08-07
**Status:** Accepted — implemented

## Context

Sophi has been driven through `sophi-cli` (terminal) and `sophi-web` (HTTP/SSE).
`sophi-sdk` — the `RuntimeBuilder`/`SophiRuntime` embeddable library — has existed
since M4 but has never had a real consumer of its own: its only exercisers were its
own test suite and the README's illustrative snippet. The goal here was a small
native desktop app that lives in the OS tray/menu bar on macOS, Linux, and Windows —
chat, session management, MCP server management, and goal/task runs, with several
sessions able to work in the background at once — built by *embedding* the SDK, not
by calling `sophi-web` over HTTP.

The interesting part is what that exposed. A library's public surface is only as
good as its first non-trivial consumer, and this was it. Four things the UI needed
turned out to be unreachable from outside `sophi-sdk`: `McpClientManager` is a
`private` constructor field with no live connect/disconnect path (`RuntimeBuilder`
connects every configured server once, at `build()` time, and that's the only
mechanism); `ScheduleEngine`'s constructor needs the provider, tool registry,
session manager, model, and context window — all things only `RuntimeBuilder`'s
`build()` scope had; `ToolRegistry` had no removal path at all, so disabling an MCP
server could stop future connections but not drop its already-registered tools; and
`SessionManager` had `create`/`save`/`load`/`list` but no `rename`/`delete`. A
latent bug surfaced alongside those: `SessionManager.create(title)` accepted a
title, `AgentSession` carried it, and `FileSessionManager.save()` never wrote it
anywhere — every session title silently evaporated on the first save/load round
trip. Nothing in the CLI or web UI displayed titles from disk, so nothing had ever
noticed.

## Decision

1. **`sophi-companion` is a standalone Gradle module, deliberately outside the root
   Maven reactor.** Compose Multiplatform Desktop is a Gradle plugin with no
   first-class Maven equivalent, and native packaging runs through that plugin's
   `compose.desktop.nativeDistributions` block (which drives `jpackage`). ADR-003
   chose Maven for the reactor and there was no reason to reverse that decision for
   one UI module, so `sophi-companion/` sits beside the reactor with its own
   `settings.gradle.kts`/`build.gradle.kts` and consumes
   `dev.sophi:sophi-sdk:1.0.0-SNAPSHOT` through `mavenLocal()`. The cost is an
   explicit two-step workflow — `mvn install -DskipTests` from the repo root, then
   `./gradlew run` from `sophi-companion/` — and it is a real cost: any change to
   `sophi-core`/`sophi-ai`/`sophi-mcp`/`sophi-schedule`/`sophi-sdk` requires the
   reinstall before the companion sees it. The Gradle build also has to declare
   `repo.spring.io/milestone` itself, because `sophi-ai` depends on Spring AI
   2.0.0-RC1 (a milestone build) and Gradle resolves `sophi-sdk`'s transitive POM
   dependencies rather than trusting that `mvn install` already had them.

2. **The companion embeds `sophi-sdk` in-process; routing through `sophi-web`'s HTTP
   API was explicitly rejected.** Going over HTTP would have been less work — the
   endpoints already exist — but it would have made the companion a fourth client of
   `sophi-web` rather than the first real client of `sophi-sdk`, and would have
   proven nothing about whether the SDK is actually embeddable. Embedding is the
   whole point of the exercise: everything in decision 3 below is a gap that only an
   in-process consumer could have found.

3. **The gaps the embedding exposed were fixed in the owning modules, additively,
   rather than worked around in the companion.** Each addition is backward
   compatible — no existing call site, production or test, changed:
   - `ToolRegistry.unregister(name)` (`sophi-core`) — returns `this` for chaining,
     mirroring `register`; a no-op for unknown names.
   - `McpClientManager` (`sophi-mcp`) moved from a flat `List<McpSession>` to a
     `Map<String, McpSession>` keyed by server name, and gained
     `connectOne(config)`/`disconnect(serverName)`. The existing
     `connect(configs)` is now `configs.flatMap { connectOne(it) }`, preserving its
     per-server failure tolerance exactly.
   - `McpServerConfig.enabled: Boolean = true` plus `McpConfigWriter.write(path,
     config)` (`sophi-mcp`) — the write-back half of the long-standing
     `McpConfigLoader`. A file with no `enabled` key still deserializes to `true`.
   - `SessionManager.rename(sessionId, title)`/`delete(sessionId)` (`sophi-core`),
     with default no-op interface bodies, following the established
     `saveConfigSnapshot(...) {}` pattern so no other implementation breaks.
     `FileSessionManager` implements both against the same `validateId` path-escape
     guard the rest of the class uses, and `SessionSidecar` gained a `title` field so
     titles finally survive save/load and appear in `SessionMeta`.
   - `SophiRuntime.connectMcpServer(config)`/`disconnectMcpServer(serverName)` and
     `scheduleEngine(taskStore, runLog, notifier, maxConcurrentTasks = 4)`
     (`sophi-sdk`). `connectMcpServer` returns the newly registered tool names;
     `disconnectMcpServer` closes the session and unregisters every tool whose name
     starts with `"${serverName}__"` — which works because `McpTool` already
     namespaces every remote tool that way.

   The `scheduleEngine` factory forced two new `SophiRuntime` constructor
   parameters, `provider: LLMProvider?` and `contextWindowTokens: Int`, both trailing
   and defaulted; `RuntimeBuilder.build()` now passes them. Both new methods fail
   loudly (`requireNotNull`/`require` with a message naming the builder call that
   was missed) rather than returning null or a degraded object, because a runtime
   built without MCP or without a provider is a wiring mistake, not a runtime
   condition.

   One gap was left alone: `SophiRuntime.sessionManager` is still `internal`, so the
   companion constructs its own `FileSessionManager` over the same `sessionsDir` for
   the Sessions tab. Two instances over one directory is safe here —
   `FileSessionManager` is stateless over the filesystem — and widening that field to
   `public` is a bigger API decision than this ADR should make on the way past.

4. **One coroutine per turn, one `StateFlow` per session.** `CompanionRuntime` holds
   a single `CoroutineScope(Dispatchers.Default + SupervisorJob())` and a
   `MutableStateFlow<SessionState>` (`Idle`/`Running`/`NeedsConfirmation`/`Error`)
   plus a `MutableStateFlow<List<String>>` message history per session id.
   `sendMessage()` sets `Running`, launches `sophiRuntime.turn(sessionId, input)` on
   that scope, and returns immediately — so the UI thread never blocks and two
   sessions' turns genuinely overlap. This is safe because `AgentLoop.turn()` keeps
   no shared mutable state across calls beyond collaborators that are already safe to
   share (`ToolRegistry`, `LLMProvider`, and a `SessionManager` where each session
   reads and writes its own file). The claim is verified rather than asserted:
   `CompanionRuntimeTest` runs two sessions against a fake provider with a 200ms
   delay and asserts the pair completes in under 350ms, which sequential execution
   (≥400ms) cannot do. `SupervisorJob` means one session's failure cancels only that
   session, and `close()` cancels the scope so in-flight turns don't outlive the app.

5. **The companion does not call `RuntimeBuilder.mcpConfig(path)`.** That method
   connects every server in `.sophi/mcp.json` unconditionally at build time, which
   would silently ignore the new `enabled` flag. Instead `CompanionRuntime`'s `init`
   block loads the config itself and calls `sophiRuntime.connectMcpServer(...)` only
   for enabled servers, each wrapped in `runCatching` so one bad server doesn't stop
   the app from starting. Add/remove/toggle in the MCP tab then write the file via
   `McpConfigWriter` and reconnect live, with no restart. `RuntimeBuilder`'s
   connect-everything behavior is unchanged for existing callers — this is a
   deliberate opt-out by one consumer, not a change of the default.

6. **Cross-platform notifications are additive; `MacNotifier` is untouched.**
   `NativeNotifications.send(title, body, ...)` shells out to `osascript` on macOS
   and `notify-send` on Linux, and calls AWT `SystemTray.displayMessage` on Windows
   (which works there, unlike on macOS). `CrossPlatformNotifier : Notifier` wraps it
   for `ScheduleEngine`. The OS name and both dispatch mechanisms are injectable
   parameters, so the tests assert the constructed command rather than firing real
   notifications — the same technique `MacNotifier`'s existing tests use. Every path
   is wrapped in `runCatching`: a missing `notify-send` degrades to no notification,
   never a failed turn.

7. **`GuiConfirmationPolicy` ships with its notification half working and its
   approval half stubbed to always-approve.** This is the one place the
   implementation falls short of the design, and it is worth being precise about
   why. `ConfirmationPolicy.confirm(requests)` (ADR-016) receives a batch of
   `ConfirmationRequest`s carrying `callId`/`toolName`/`argumentsJson`/`riskLevel` —
   and no session id. A single policy instance is shared across every concurrent
   session, so with concurrency (decision 4) in play there is no way to route an
   approve/deny prompt to the Chat tab of the session that raised it. The policy
   therefore fires the native "Sophi needs confirmation" notification and returns
   `true` for every request. Routing this properly means either threading a session
   id through `ConfirmationRequest` or constructing one policy per session, both of
   which are real API decisions deferred out of this iteration. Until then the
   companion is not a safe place to run destructive tools unattended, and this ADR
   says so rather than letting the running app imply otherwise.

8. **Native packaging ships in v1 rather than being deferred.** On macOS a bare
   `java -jar` process generally cannot register with Notification Center, so a
   properly bundled `.app` is close to a hard requirement for decision 6 to work at
   all — packaging is a correctness dependency here, not a distribution nicety.
   `nativeDistributions` targets `.dmg`, `.deb`/AppImage, and `.msi` with icons
   generated from `doc/images/logo.png` and bundle ID `dev.sophi.companion`. Two
   environment issues had to be worked around, both recorded in the build files
   because they will bite the next person: Homebrew's OpenJDK trips Compose Desktop's
   `jpackage` vendor safety check (fixed with the documented
   `compose.desktop.packaging.checkJdkVendor=false` in `gradle.properties`), and
   Gradle 9.x's stricter task-output validation flags an implicit dependency Compose
   Multiplatform 1.9.0's plugin doesn't declare between the per-format packaging
   tasks and `:packageAppImage`'s shared output directory (fixed with an explicit
   `dependsOn`). The `dependsOn` must be registered *lazily* —
   `tasks.matching { }.configureEach { }`. An eager
   `tasks.findByName(name)?.dependsOn(...)` compiles, runs, and silently wires
   nothing, because Compose registers the per-format packaging tasks after the build
   script is evaluated, so every lookup returns `null` and the safe-call swallows it.
   The first form shipped in the initial packaging commit and was only caught when a
   later clean `packageDistributionForCurrentOS` run failed with the exact validation
   error it was supposed to prevent; the build file now carries a comment saying so.

## Consequences

- `sophi-sdk` is now genuinely embeddable for a stateful, long-lived, interactive
  host process, not just for a request-scoped Spring `@Service`. The five methods
  added in decision 3 are available to every SDK consumer, not just this one.
- `sophi-schedule`'s `ScheduleEngine` gets its first long-running host:
  `CompanionRuntime.startSchedulePolling()` calls `tickOnce()` on a 30-second loop.
  Previously the engine was only ever driven ad hoc (`runNow`) or by an external OS
  scheduler, per ADR-014.
- Session titles now persist. Any code reading `SessionMeta.title` or
  `AgentSession.title` after a load will start seeing non-null values where it
  previously always saw `null`. Nothing currently depends on that being null, but
  it is a behavior change with blast radius beyond the companion.
- The build is no longer single-command. `mvn install` before `./gradlew` is a real
  ordering constraint, and CI does not build `sophi-companion` at all — it is not in
  the reactor.
- The confirmation stub (decision 7) means the companion always approves non-`SAFE`
  tool calls. Deliberate for this iteration, blocking on the API question above.
- Tray click behavior differs by OS and we did not normalize it. On macOS,
  left-clicking an AWT `SystemTray` icon opens its context menu rather than firing
  `onAction`, so "Open Sophi" in the menu is the reliable path there; Linux and
  Windows are expected to fire `onAction` on click. This is AWT behavior underneath
  Compose's `Tray`, not something the app chooses.
- Only the macOS bundle was actually built and launched. The `.deb`/AppImage/`.msi`
  targets share one `nativeDistributions` block and are configured, not verified —
  no Linux or Windows machine was in the loop. Likewise, no screen-recording access
  was available, so "the tray icon renders correctly in the menu bar" was never
  visually confirmed; verification was process liveness, log inspection, and
  launching the packaged `.app` directly to confirm it runs as a standalone binary
  with the icon embedded in `Contents/Resources`.
- The Compose UI itself has no automated tests — verification was manual, per the
  spec's testing strategy. Every non-UI class (`CompanionRuntime`, `SettingsStore`,
  `GuiConfirmationPolicy`, and all five existing-module additions) has a Kotest
  suite.
- Explicitly out of scope: Kotlin/Native or mobile/web targets (`sophi-sdk` is
  JVM/Spring-based; "multiplatform" here means one desktop codebase producing three
  native packages); auto-update; state syncing between installed copies; live reload
  of `~/.sophi/companion.json` without a restart; MCP config validation beyond what
  `McpConfigLoader` already does. The MCP tab surfaces list/enable/disable/remove —
  `CompanionRuntime.addOrUpdateMcpServer` exists and is tested, but no add/edit form
  is wired to it yet — and the Sessions tab's rename writes a fixed placeholder
  title rather than prompting for one.

## References

- ADR-003 (Maven multi-module) — the build decision `sophi-companion` sits beside
  rather than reverses; it is the only Gradle project in the repo.
- ADR-014 (scheduled & goal-based tasks) — the `ScheduleEngine`/`TaskStore`/`RunLog`/
  `Notifier` machinery the Goals tab drives, and the OS-scheduler-first tick model
  the companion's in-process poll loop is the first alternative to.
- ADR-016 (tiered tool confirmation and grants) — the batched, session-id-less
  `ConfirmationPolicy.confirm(requests)` contract that decision 7's always-approve
  stub is blocked on.
- ADR-018 / ADR-020 (plan-and-execute, goal decomposition) — what a Goal-mode task
  created from the Goals tab actually runs through, unchanged by this ADR.
- ADR-005 (JSONL tree sessions) — the session format whose sidecar file now carries
  the persisted title.
- `docs/superpowers/specs/2026-08-06-sophi-companion-design.md` — full design
  rationale, non-goals, and the concurrency-safety argument in longhand.
- `docs/superpowers/plans/2026-08-06-sophi-companion.md` — the 15-task
  implementation plan, including the Self-Review Notes recording the issues caught
  before execution (the missing `enabled` field, the Chat tab that displayed no
  replies, and the `CoroutineScope` that leaked in-flight turns on shutdown).
