# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
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
