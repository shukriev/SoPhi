---
name: sophi-architecture
description: Sophi module contracts, conventions, and ADR index for working on this codebase
tools: [read, bash, grep, find]
auto_detect: [sophi-core, sophi-ai, sophi-cli, AgentSession, LLMProvider, sophi-extensions, sophi-skills, PluginRegistry]
---

# Sophi Architecture Skill

## Module ownership

| Package | Owns |
|---------|------|
| `dev.sophi.ai` | `LLMProvider`, `CompletionRequest`, `LLMResponse`, `StreamEvent`, `ProviderRegistry`, `TokenUsage`, `ModelInfo` |
| `dev.sophi.core.session` | `SessionManager`, `SessionEntry` sealed hierarchy, JSONL persistence |
| `dev.sophi.core.agent` | `AgentLoop`, `AgentSession`, `AgentSessionRuntime` |
| `dev.sophi.core.tools` | `Tool`, `ToolResult`, `ToolExecutor`, `FileMutationQueue`, all built-in tools |
| `dev.sophi.core.context` | `ContextManager`, compaction strategy |
| `dev.sophi.core.prompt` | `SystemPromptBuilder`, `AgentsMdLoader`, `SystemMdLoader` |
| `dev.sophi.skills` | `SkillLoader`, `SkillSummary`, `Skill` |
| `dev.sophi.extensions` | `PluginRegistry`, `AgentHook`, `HookContext`, `HookPoint`, `TurnEventBridge` |
| `dev.sophi.cli` | `SophiCli`, `TuiEngine`, `SophiTerminal`, `TurnController`, `SlashHandler` (in-session `/list /branch /checkout /compact /good /bad /schedule /feedback /lessons /memory`), `ScheduleCommand`/`ScheduleDaemonCommand`/`ScheduleRunDueCommand`/`ScheduleInstallLaunchdCommand`/`GoalCommand`/`MemoryCommand`/`McpServeCommand` (top-level `sophi <name> ...` subcommands, distinct from the slash commands above) |
| `dev.sophi.web` | Spring Boot app, `AgentController`, WebSocket config, SSE endpoints |
| `dev.sophi.sdk` | `Sophi` factory object, `SessionBuilder`, `RuntimeBuilder` |
| `dev.sophi.infra` | `AuthManager`, `AuthStorage`, `BudgetTracker`, `PermissionGatePlugin` |

## Dependency rules (never violate)

- `sophi-core` NEVER imports from `sophi-web`, `sophi-cli`, `sophi-sdk`, or `sophi-infra`
- `sophi-ai` NEVER imports from `sophi-core` (dependency flows core → ai, not ai → core)
- `sophi-skills` has NO dependency on `sophi-core` — standalone YAML/Markdown loader
- All cross-module contracts are defined as interfaces in `sophi-core` or `sophi-ai`

## Conventions

- Sealed class hierarchies for all discriminated unions (`LLMResponse`, `AgentEvent`, `SessionEntry`, `ToolResult`, `Message`)
- Coroutines throughout — no blocking calls on `Dispatchers.Default` or `Dispatchers.Main`
- Tool names: `snake_case` (`read`, `write`, `bash`); interface methods: `camelCase`
- All file write operations wrapped in `FileMutationQueue` to prevent concurrent mutation
- Integration test classes end in `IT`; unit test classes end in `Test`
- Session files: `~/.sophi/sessions/{cwd-hash}/{timestamp}.jsonl`

## ADR index

| ADR | Ruling |
|-----|--------|
| ADR-001 | Write sophi-core from scratch — Koog rejected |
| ADR-002 | Spring AI starters for providers — no DIY HTTP clients |
| ADR-003 | Maven multi-module — Gradle rejected |
