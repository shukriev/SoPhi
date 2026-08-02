# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Interactive `/goal [--check "<command>"] <task>` command: explicit multi-step planning
  (`PlanRunner`) in a live chat session, with a plan preview, live per-step progress, and
  replanning on failure. See ADR-019.

### Changed

### Fixed

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
