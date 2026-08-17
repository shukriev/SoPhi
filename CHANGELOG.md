# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `sophi-hub`: local WebSocket hub letting `sophi-companion` monitor and remote-control running `sophi-cli` sessions — live status, streamed tokens, and confirmation prompts, plus sending messages into a session or answering its confirmation prompts from the companion (ADR-023)
- Interactive `/goal [--check "<command>"] <task>` command: explicit multi-step planning
  (`PlanRunner`) in a live chat session, with a plan preview, live per-step progress, and
  replanning on failure (ADR-025)

### Changed
- `PlanRunner`'s progress stream is now one seam: `PlanProgressEvent` gained `PlanReady`,
  `StepAttempt` and `Escalating`, `StepFinished`/`StepStarted` carry the plan version, and
  `Replanned` carries the replacement `Plan` rather than only its id. The separate raw
  `onEvent: (TurnEvent)` seam is unchanged (ADR-025)

### Fixed
- `sophi-cli`: a session started before `sophi-companion` never appeared in the companion's Sessions tab, even after the companion started — `HubClient.connect()` was only attempted once, at CLI startup. The CLI now retries the hub connection on a timer for the life of the session (ADR-023)

### Deprecated

### Removed

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
