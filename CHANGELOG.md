# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `ask_user` is in core: a host passes a `UserAsker` with `RuntimeBuilder.askUser(asker)`, and the tool is registered only then. Scheduled runs are marked `Unattended`, so their questions time out after 4 minutes and the model carries on.
- `todo`: a checklist the model keeps for multi-step tasks. It lives in the session, so resume and `/checkout` bring it back, and compaction carries it into its summary; the system prompt never changes. The CLI prints the list under a turn that changed it.
- Background `bash`: `bash(background = true)` returns a job id, `bash_output` reads what it printed since last time (waiting up to 120 s for more), `bash_kill` stops it, and `/jobs` lists and stops jobs in the CLI. At most 8 run at once, and every job dies with Sophi.
- `apply_patch`: adds, updates, moves and deletes several files in one all-or-nothing call (the Codex/OpenCode patch format). Hunks match through whitespace slips and keep the file's indentation and line endings; deletes go to the SoPhi trash, and the call is one undo step.

### Removed
- `BudgetTracker` (sophi-infra), a token counter nothing used; `RuntimeBuilder.budget(maxUsd)` replaces it

### Changed
- Session files are append-only: a save appends only new or changed entries instead of rewriting the file, so its cost no longer grows with the session. A crash mid-write loses at most the last, partial line. The checked-out tip is saved in `.meta.json`, and `/checkout` saves it, so resuming returns to that entry. Two copies of one session saved in turn no longer drop each other's entries. Old session files load as before.
- **Breaking:** local models are the default. `--provider` defaults to `local`, which finds llama.cpp (:8080), LM Studio (:1234) or Ollama (:11434), picks a model the server has loaded, and reads the context window it was loaded with. No surface defaults to a Claude model any more:
  - `--model` is required with `--provider claude` and `--provider openai-compat`;
  - a `--base-url` user now also passes `--model`, or switches to `--provider local`, which reads the model from the server;
  - **SDK:** `RuntimeBuilder.model` must be set, or `build()` fails;
  - **sophi-web:** `sophi.provider.model` is required, or startup fails;
  - **launchd/cron:** `sophi schedule install-launchd` writes `sophi schedule run-due` with no provider flags, so an existing install that relied on the Claude default now looks for a local server. To keep Claude, add `--provider claude --model <name>` after `run-due` in `ProgramArguments` of `~/Library/LaunchAgents/dev.sophi.schedule.plist`, then `launchctl unload` and `launchctl load -w` it. `run-due` now only resolves a provider when a task is due;
  - a self-mod gate run against a baseline jar from before this change fails once, because that jar's `evals run` doesn't know `--provider local`; rebuild the baseline.
- With stock Ollama, start it with `OLLAMA_CONTEXT_LENGTH=16384 ollama serve`: its default 4096-token window is smaller than SoPhi's tool definitions alone, and SoPhi warns when it detects it
- `evals run`, `tournament run` and `skill verify` cap their fixed 100000-token window at the provider's window (detected, or `--context-window-tokens`)
- Memory and versioning live on the shared `~/.sophi/db` server: the CLI and the companion can both use memory at the same time — no more "memory: disabled … only one process" (ADR-041, M6). Run the same version of the CLI and the companion: a differing schema is refused
- One-time, automatic move of `~/.sophi/memory`, `~/.sophi/memory/.versions` and `~/.sophi/versioning` onto the shared server; the old directories are kept as `arcadedb.migrated-<timestamp>`, and a plain file at `<home>/arcadedb` makes an older (pre-M6) build show memory and versioning as disabled instead of starting an empty palace. A directory an older build recreates anyway is kept as `arcadedb.stray-<timestamp>` and never merged into the shared store; a self-mod gate run against a pre-M6 baseline jar fails until the baseline is upgraded
- Context index schema v2 (ADR-041, M5): the first start after upgrading re-indexes every project once, and a client refuses a host whose schema version differs (either direction), so run the same version of the CLI and the companion
- Compaction summaries are no longer SYSTEM messages: mid-turn they are appended to the turn's user message, cross-turn they are a leading USER/ASSISTANT pair, so strict chat templates accept them (ADR-041, M4)
- Compaction elides old tool output before summarising (no LLM call when that is enough), and an oversized parallel round of tool results is stubbed instead of ending the turn with `ContextExhausted` in the `elide` and `structured` modes; E4 on a small local model in `evals/context/RESULTS.md`
- Context hint gate: the top-1/top-2 ratio threshold rises from 1.0 to 1.25 — live E1 negatives showed the old gate firing on 6 of 10 chit-chat messages against the SoPhi index, the new one on none; live E1/E5 results on a small local model recorded in `evals/context/RESULTS.md` (ADR-041)

### Added
- Model catalog, cost and budget. Each reply ends with its tokens and, on cloud providers, what the turn and the session cost; local models show tokens/second instead.
  - Prices, windows and capabilities come from a bundled [models.dev](https://models.dev) snapshot, refreshed in the background at most daily into `~/.sophi/models.json` (`--no-catalog-refresh` to stay offline), with `~/.sophi/models.local.json` overriding entries. `sophi models <query>` looks one up and `sophi models --refresh` refreshes now.
  - Cloud windows come from the catalog instead of a 200000/8192 guess. A local model's window still comes from its server, and the catalog warns when its base model can't call tools.
  - `--max-cost-usd` and the live `max-cost-usd` setting stop the session once it has cost that much, counting `/goal`, `/plan`, subagents and compaction. `/model <id>` switches the model mid-session.
  - SDK: `ModelCatalog`, `RuntimeBuilder.modelCatalog/priced/catalogProvider/budget(maxUsd)`, `SophiRuntime.costMeter/switchModel/provider`, `MeteredProvider`, `TurnStopReason.BudgetExceeded`, `TurnEvent.RoundUsage`
- `/settings`: a menu for every `sophi` start flag, saved to `~/.sophi/settings.json` as that flag's default (a typed flag still wins). The sandbox, network, Docker image and auto mode change the running session at once; the rest apply at the next start. `/settings <key> <value>` sets one without the menu. API keys, `--session` and `--god-mode` are never saved; a value that doesn't fit is skipped with a warning. `--no-auto`, `--no-memory`, `--no-auto-exit-token-view`, `--no-god-mode` and `--remote` switch a saved on-flag off for one run
- `bash` sandbox modes: `--sandbox seatbelt` (macOS) or `--sandbox docker` (one locked-down container per session sharing only the workspace; `--docker-image`), with `--no-network` to block the network and name lookups. Seatbelt also blocks Docker's socket. Path checks still apply. SDK: `SophiRuntime.bashSandbox`, `BashSandbox.set(mode, network, image)`
- Type while a turn runs: Enter steers the running turn (read before its next step), Alt+Enter queues a follow-up turn, the queue shows under the spinner, ESC returns unsent text to the prompt (kept even if a companion message arrives first), and a multi-line paste stays one message. The token-view toggle moves to Ctrl+T. SDK: `SophiRuntime.streamTurn(…, inbox = TurnInbox())`, with `TurnEvent.SteerApplied` and `TurnEvent.FollowUpStarted`
- `sophi lessons forget <id>` and `/lessons forget <id>`: retract a lesson for good. `LessonStore.forget` marks it `forgotten`; every look-back lists forgotten lessons (this scope and global) as never to re-emit, and a lesson with the same words is dropped. `archive` still only stops recall.
- Permission rules: `~/.sophi/permissions.json` and `.sophi/permissions.json` hold `allow`/`ask`/`deny` rules per tool and pattern (deny beats ask beats allow, also under `--god-mode` and in `mcp-serve`; an ask rule always reaches a person, in auto and god mode too). Project `allow` rules need `/permissions trust`; answering `a` at a confirmation prompt writes one. `/permissions` lists them; a malformed file stops startup. SDK: `RuntimeBuilder.permissionRules()`. See the README for the known limits.
- Audit log: every tool call, approved or denied, is appended to ~/.sophi/audit.jsonl with actor, rule and result; secrets are redacted.
- Undo for file changes: workspace checkpoints, move_file/delete_file with a SoPhi trash, /undo, /revert, /checkout --files.
- `tool_search` and deferred MCP tools: a server that lists more than 10 tools (`deferToolsOver` at the top of `.sophi/mcp.json`, or `RuntimeBuilder.deferToolsOver(n)` in the SDK) keeps its schemas out of the prompt; the model sees the names and loads the ones it needs with `tool_search`. Applies to the CLI, the SDK and sophi-web
- Local models first-class: `sophi-ai` recovers tool calls written into the text (Hermes `<tool_call>`, Mistral `[TOOL_CALLS][…]`), splits `<think>` reasoning out of the answer, and returns malformed tool arguments to the model as a tool error it can retry
- `sophi-store`: `SharedArcadeHost`, `FailoverArcadeStore`, `LegacyMigration`/`SharedStore`; per-database schema check in `host.json`
- Context graph and semantics (ADR-041, M5): `find_symbol` and `related` tools over Symbol/Relation documents (imports, links, tests), the `SOPHI_CONTEXT_TOOLS` knob (`all` default, `core`, `none`), optional local chunk embeddings (`SOPHI_CONTEXT_EMBED`, loopback only, off, experiment-only: inconclusive in E2) fused with BM25 by RRF, summaries as an indexing input (off by default), and the `--summaries`/`--embed` flags and docs/commits sets of `sophi context eval`; results in `evals/context/RESULTS.md`
- Automatic cross-turn compaction in every SDK runtime, structured summaries (Goal, Decisions & findings, Files touched, Commands & outcomes, Errors & attempts, Open items — the file and command lists computed from the real tool calls, not the model's memory), and the internal `SOPHI_COMPACTION` knob: `structured` (default) | `elide` | `generic` (ADR-041, M4)
- Context engine, session scope and management surface (ADR-041, M3): a session searches its own project plus every project marked global, and is oriented on its own project only; `sophi context global <id|dir> on|off` (and `list` shows `global`); `ContextEngine.status()` (role, per-project info, disk size), `setGlobal`, `unavailableReason` and scan progress; `IndexReset` is now a library call in `sophi-context` so apps can reset the index
- Context orientation: global and project instruction files (`AGENTS.md`, `CLAUDE.md`, `.sophi/instructions.md`) framed as project conventions plus a compact project map in the system prompt, within a token budget; and a gated per-turn context hint of up to 3 file pointers when a message clearly matches the indexed project (ADR-041, M2)
- `sophi-context`: a lexical context index over your projects — `search_context`, `read_context` and `project_map` tools and `sophi context add|list|remove|reset|eval`; the working directory is registered automatically only inside a git repo (or a folder with an instruction file), never `$HOME` or `/`; retrieval scored by `sophi context eval` (ADR-041)
- `sophi-store`: the `ArcadeHost` library for one ArcadeDB store shared across processes — the first to lock `~/.sophi/db/host.lock` hosts a loopback server, others attach remotely and take over if the host exits (CLI/companion wiring comes in M1); full-text and vector-index primitives that work the same embedded and remote (ADR-041)
- `sophi-hub`: local WebSocket hub letting `sophi-companion` monitor and remote-control running `sophi-cli` sessions — live status, streamed tokens, and confirmation prompts, plus sending messages into a session or answering its confirmation prompts from the companion (ADR-023)
- Interactive `/goal [--check "<command>"] <task>` command: explicit multi-step planning
  (`PlanRunner`) in a live chat session, with a plan preview, live per-step progress, and
  replanning on failure (ADR-025)
- Memory browsing can filter by originating session: `BrowseFilter.sourceSessionId`, exposed as
  `sophi memory list --source=ambient` and `/memory list --source=ambient`, which selects what
  ambient listening overheard. `MemoryView` metadata gained a `source` key. `sophi memory list`
  also gained the `--provenance` filter ADR-034 left open (ADR-036)
- `ForgetRequest.ById(soft = true)`: a reversible delete that sets `softDeletedAt` so `restore()`
  can undo it within the grace period. The default stays permanent (ADR-036)

### Changed
- **Breaking (SDK):** `RuntimeBuilder.context(engine, owned, project)` now takes `project: ProjectOf` — a function from session id to project id (or null) — instead of `scope: () -> List<String>`; callers must pass a lambda (ADR-041, M3)
- Memory, lesson and site-skill recall now ride on the current turn's user message (a `<context>` block in the request only, never persisted) instead of the system prompt, so the system prompt is stable across turns (ADR-041)
- Anthropic prompt caching is enabled on every request (system prompt and tools); token usage counts all prompt tokens and reports cache reads; opt-in `SOPHI_USAGE_LOG` writes a numbers-only JSONL (ADR-041)
- `PlanRunner`'s progress stream is now one seam: `PlanProgressEvent` gained `PlanReady`,
  `StepAttempt` and `Escalating`, `StepFinished`/`StepStarted` carry the plan version, and
  `Replanned` carries the replacement `Plan` rather than only its id. The separate raw
  `onEvent: (TurnEvent)` seam is unchanged (ADR-025)

### Fixed
- Steering: a confirmation now waits until you stop typing for half a second, not just half a second after it appeared, so a steer typed past that point can't answer it
- Steering: several steers queued in one round go to the model as one user message; strict chat templates such as Gemma's rejected the request before
- Learning: a look-back at the same session no longer re-records a correction it already recorded, so one correction can't corroborate a preference lesson on its own
- Learning: ambient turns no longer write `session-outcomes.jsonl`/`tool-events.jsonl` lines or keep a per-session counter that never ended
- `sophi-cli`: a session started before `sophi-companion` never appeared in the companion's Sessions tab, even after the companion started — `HubClient.connect()` was only attempted once, at CLI startup. The CLI now retries the hub connection on a timer for the life of the session (ADR-023)
- Ambient listening could write overheard speech down as facts about the user. Provenance left unstated by the encoder defaulted to `USER_DIRECT` — the one value that opens the ADR-035 commitment gate — so an omitted field became a user commitment; it now resolves to `THIRD_PARTY` on ambient turns. Profile evidence was written from ambient turns unconditionally, so a guest's remark could become one of the user's stable traits; ambient turns now write none. The ambient encoder prompt also rejects broadcast media (TV, podcasts, lyrics, ads), which a microphone cannot distinguish from people in the room (ADR-037)

### Deprecated

### Removed
- `sophi-companion` is no longer part of this repo — it's moved to a private repo as a commercial, direct-sale product. The engine it's built on (`sophi-sdk`, `sophi-hub`, `sophi-core`, etc.) is unaffected and stays open. Last open-source snapshot: tag `companion-last-oss`.

### Security

## [1.0.0] - 2026-07-19

### Added
- Initial public release of SoPhi — Kotlin-native agent harness for the JVM
- `sophi-core`: Agent loop with tool calling, branchable session persistence, context compaction
- `sophi-ai`: LLM provider abstraction with Claude and OpenAI-compatible implementations
- `sophi-cli`: Interactive terminal UI with persistent sessions and slash commands
- `sophi-web`: Spring Boot REST/SSE server for agent-over-HTTP
- `sophi-sdk`: RuntimeBuilder DSL for embedding agents in JVM apps
- `sophi-skills`: Markdown-based capability packages with YAML frontmatter
- `sophi-extensions`: Plugin lifecycle hooks (BEFORE_TURN, AFTER_TOOL, ON_ERROR, etc.)
- `sophi-learning`: Self-learning system with tool reliability tracking and lesson distillation
- `sophi-memory`: Long-term memory (Jane's Theory) with memory palace architecture
- `sophi-mcp`: MCP client and server integration
- `sophi-infra`: Ready-made plugins (PermissionGatePlugin, MetricsPlugin, BudgetTracker)
- Support for local models via Ollama and vLLM with `--provider openai-compat`
- Subagent delegation with Markdown-based agent definitions
- Session branching, checkout, and context compaction (`/compact` command)
- Architecture deep-dive in `doc/Architecture.md`
- README with three primary use cases (CLI, HTTP server, embedded SDK)
- Contributing guidelines and governance documentation
- Contributor Covenant Code of Conduct

[Unreleased]: https://github.com/shukriev/SoPhi/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/shukriev/SoPhi/releases/tag/v1.0.0
