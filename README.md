<div align="center">

<h1>
  <img src="doc/images/logo.png" width="40" valign="middle" alt="SoPhi logo"/>
  SoPhi
</h1>

### The Kotlin-native agent harness for the JVM

**Bring your own LLM. Ship an agent in three lines, not three weeks.**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0-6DB33F)](https://spring.io/projects/spring-ai)

[Architecture deep-dive →](doc/Architecture.md)

</div>

---

SoPhi is a set of small, composable Maven modules built around one
battle-tested core: an agent loop with tool calling, branchable session
persistence, and automatic context compaction. Wrap it in a **terminal
app**, a **REST/SSE server**, or **embed it directly** in your own JVM
app — same core, three ways to run it.

## 🧩 Modules

| Module | What it gives you |
|--------|--------------------|
| ⚡ `sophi-ai` | Provider abstraction (`LLMProvider`) + Spring AI-backed `ClaudeProvider` / `OpenAICompatProvider` |
| 🧠 `sophi-core` | The agent loop, session tree (JSONL, branch/checkout), tool dispatch, context compaction, explicit multi-step planning (`PlanRunner`) |
| 📚 `sophi-skills` | Load capability packages from Markdown files with YAML frontmatter |
| 🔌 `sophi-extensions` | `SophiPlugin` / `AgentHook` — lifecycle hooks (`BEFORE_TURN`, `AFTER_TOOL`, `ON_ERROR`, ...) |
| 🎓 `sophi-learning` | Self-learning: tool reliability stats, session-end lesson distillation, user feedback, fine-tuning dataset export |
| 🗄️ `sophi-store` | Embedded ArcadeDB (document+graph+vector) behind a generic `ArcadeStore` layer — the storage backend under memory and versioning |
| 🕰️ `sophi-versioning` | Generic `Version`/`VersionStore` history + revert for skills, lessons, configs, and agent definitions, plus a config tournament mechanism (`sophi versions`, `sophi tournament`) |
| 🏛️ `sophi-memory` | Long-term memory (Jane's Theory): a memory-palace store behind a technique-agnostic `MemoryTechnique` SPI |
| 🔗 `sophi-mcp` | MCP client + server — call external MCP tool servers from the agent, or expose SoPhi over MCP (`sophi mcp-serve`) |
| 📡 `sophi-hub` | Local WebSocket hub (`HubServer`/`HubClient`) — lets `sophi-companion` monitor and remote-control running `sophi-cli` sessions |
| 💻 `sophi-cli` | `sophi` terminal app — interactive TUI with slash commands |
| 🌐 `sophi-web` | Spring Boot REST + SSE server exposing sessions and turns over HTTP |
| 🛠️ `sophi-sdk` | `Sophi.runtime { }` DSL for embedding the agent in another JVM app |
| 🖥️ `sophi-companion` | *(moved)* OS tray / menu-bar desktop app — now a commercial, direct-sale product built on `sophi-sdk`/`sophi-hub`. Last open-source snapshot: tag [`companion-last-oss`](../../tree/companion-last-oss/sophi-companion). |
| 🏗️ `sophi-infra` | Ready-made plugins and trackers: `BudgetTracker`, `MetricsPlugin` |

They all sit on the same core, so switching between them later is a
one-line change, not a rewrite.

## 📦 Install

**Installers (no Java needed).** Download from the newest `cli-v*`
[release](../../releases): `sophi-macos-arm64.pkg` (Apple Silicon), `sophi-macos-x64.pkg`
(Intel) or `sophi-windows-x64.msi`, open it, then run `sophi` in a new terminal. Until code signing is set up,
they are unsigned: on macOS approve the package once under System Settings → Privacy & Security;
on Windows choose "More info → Run anyway". To uninstall on macOS, delete `/Applications/sophi.app`
and `/usr/local/bin/sophi`; on Windows use Apps & features.

**One-liner (needs Java 21+).** No checkout required.

```bash
# macOS / Linux
curl -fsSL https://raw.githubusercontent.com/shukriev/SoPhi/main/scripts/get-sophi.sh | sh
```

```powershell
# Windows (PowerShell)
irm https://raw.githubusercontent.com/shukriev/SoPhi/main/scripts/get-sophi.ps1 | iex
```

Re-run to upgrade; set `SOPHI_VERSION=cli-v<x.y.z>` to pin a release. Maintainers publish
a release by pushing a `cli-v*` tag (`.github/workflows/cli-release.yml`).

## 🚀 Build


```bash
mvn -q -DskipTests package
```

SoPhi runs on a local model by default: start Ollama, LM Studio or llama.cpp and it finds
the server itself. Claude is opt-in (`--provider claude --model <name>` with
`ANTHROPIC_API_KEY` set); SoPhi never picks a cloud model for you.

---

## 💻 Use case 1: Interactive terminal agent (`sophi-cli`)

Run a chat session in your terminal, backed by a local model, with persistent
sessions you can branch and resume.

Quick start: start Ollama or LM Studio (with a model loaded), then run `sophi`.

```bash
OLLAMA_CONTEXT_LENGTH=16384 ollama serve & ollama pull qwen3:8b   # or load a model in LM Studio
mvn -pl sophi-cli -am package
java -jar sophi-cli/target/sophi-cli-1.0.0-SNAPSHOT.jar
```

SoPhi probes llama.cpp (:8080), LM Studio (:1234) and Ollama (:11434), uses a model the
server has in memory (else the first one it lists), and reads the context window the
server loaded it with. Ollama's own default window is 4096 tokens, smaller than SoPhi's
tool definitions alone, hence `OLLAMA_CONTEXT_LENGTH` above.

To use Claude instead:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
sophi --provider claude --model <claude-model-id>
```

Options:

```bash
sophi --session <id>          # resume an existing session
sophi --model <name>          # model (local: defaults to a model the server has loaded; required otherwise)
sophi --sessions-dir <path>   # where session JSONL files live (default: ~/.sophi/sessions)
sophi --agents-dir <path>     # directory of subagent definitions (default: ~/.sophi/agents)
sophi --system "<prompt>"     # system prompt for every turn
sophi --provider <name>       # 'local' (default: finds llama.cpp, LM Studio or Ollama), 'openai-compat', or 'claude'
sophi --base-url <url>        # local: skip discovery and use this server; required for openai-compat
sophi --context-window-tokens <n>  # override the window (local: read from the server; openai-compat 8192; claude 200000)
sophi --api-key <key>         # optional; falls back to SOPHI_API_KEY (keeps it out of `ps`), then ANTHROPIC_API_KEY for claude
sophi --memory                # enable long-term memory (see "Cross-cutting: memory")
sophi --embedding-model <m>   # embedding model for --memory (local: a loaded nomic-embed model is used if present)
sophi --embedding-base-url <url>   # embeddings endpoint (defaults to --base-url)
sophi --embedding-dimensions <n>   # vector size (768 nomic-embed-text, 1536 OpenAI; default 1536)
sophi --hub-port <port>       # port a running companion's hub listens on (default: 8765)
sophi --no-remote             # don't register with a running companion's hub for this session
```

Other servers and models:

```bash
# A specific model on the local server SoPhi finds
sophi --model qwen2.5:7b

# Any other OpenAI-compatible server, e.g. vLLM
sophi --provider openai-compat --base-url http://localhost:8000/v1 --model Qwen/Qwen2.5-7B-Instruct
```

In-session slash commands:

```
/list       list saved sessions
/branch     print the active branch (entry ids + roles)
/checkout <entry-id> [--files]   jump to a different point in the session tree; --files also restores the files
/undo       restore the files the last tool round changed (asks first)
/revert <entry-id>   restore the files changed from that entry on
/compact    summarize older turns to shrink context
/good       rate the last reply as good (feeds the learning system)
/bad [reason]   rate the last reply as bad, optionally saying why
/schedule [list|log|pause <id>|resume <id>|remove <id>]   manage scheduled/goal tasks
/goal [--check "<command>"] <task>   explicit multi-step planning, with a preview before running
/plan <goal>   break a goal into steps and run them one by one
/calendar [list [days]|get <id>|create <description>|delete <id>|calendars]   manage calendar events (macOS)
/skill [list|<id>]   list installed skills, or load one into the session
/auto       toggle auto mode: low-risk tool calls run without asking (not under --god-mode)
/permissions [trust]   list permission rules; trust activates the project's allow rules
/settings [key [value]] open the settings menu, or show or set one setting
/feedback [list|delete <id>]   inspect and manage explicit feedback records
/lessons [list|archive <id>|forget <id>]   inspect and manage learned lessons
/memory [list|show <id>|threads|profile|why]   browse Jane's Theory memory (needs --memory)
exit / quit  end the session
```

**Audit log.** The CLI appends every tool call, approved or denied, to `~/.sophi/audit.jsonl`, one JSON line each: `ts`, `actor`, `tenant`, `session`, `tool`, `risk`, `args`, `decision`, `rule` (what decided: `user`, `grant`, `policy`...), `outcome` (`ok`, `error`, `denied` or `cancelled`), `result` and `durationMs`. Secrets in args and results are redacted. Scheduled tasks are audited too, and so are calls from external MCP clients to `sophi mcp-serve` (actor `mcp:<client name>`, or `mcp-client` when it gives none; `rule` `safe` or `deny-all`). In the SDK, `RuntimeBuilder.auditLog()` turns it on, or `audit(sink)` takes your own `AuditSink`.

Known limits of the audit log:

- Redaction is pattern-based and best effort.
- `args` and `result` keep a bounded sample (4,000 and 500 chars) and redaction work per entry is capped. Past the cap, the rest of each object or array, keys and values alike, is replaced by one `"…": "[…]"` (or `"[…]"`) marker.
- Arguments that aren't JSON get the weaker text redaction, so numeric secrets and secrets under unfamiliar key names can survive.
- A string value that starts with `{` or `[` (after whitespace, a BOM or a code fence) is redacted as JSON, up to 3 levels deep; if it doesn't parse (a cut JSON document) it is logged as `[unredactable]`. Other strings, such as code containing `{"a": 1}`, keep text redaction. A whole argument that isn't JSON but contains `{"` or `["`, such as a raw `curl -d '{...}'`, is logged as `[unredactable]`.
- A numeric secret under an ordinary key name (e.g. `{"pin":123456}`) is logged as-is; numbers over 64 digits show as `[…]`.
- Keys that redact to the same text (two GitHub tokens used as keys, say) are numbered `#2`, `#3`, so no value is lost.
- A value that can't be redacted safely is logged as `[unredactable]`.
- Secrets in shapes the patterns don't know (custom formats, encoded or split values) can still appear.
- Calls made through sophi-web are not audited yet.
- A call cancelled before it starts (e.g. during confirmation) has no entry.

**Permission rules.** `~/.sophi/permissions.json` (global) and `.sophi/permissions.json` (project) hold a JSON list of `{"tool", "pattern", "effect"}`, where `effect` is `allow`, `ask` or `deny`. `tool` and `pattern` are globs: `*` matches anything, including `/`, and `\*` is a literal star. A rule without `pattern` covers every call of that tool.

```json
[
  {"tool": "bash", "pattern": "rm *", "effect": "deny"},
  {"tool": "bash", "pattern": "git status", "effect": "allow"},
  {"tool": "write_file", "pattern": "secrets/*", "effect": "deny"},
  {"tool": "read_file", "pattern": "/Users/you/.ssh/*", "effect": "deny"}
]
```

The order is deny, then ask, then allow, then the usual risk tiers and confirmation. A deny rule also blocks under `--god-mode` and in `mcp-serve`; an ask rule always sends the call to a person, also in auto mode and under `--god-mode`. What a pattern is matched against:

- `bash`: each part of the command, after splitting on `&&`, `||`, `;`, `|`, newlines, `$( )`, backticks and subshells. A call is allowed only if every part is.
- `read_file`, `write_file`, `edit_file`, `grep`, `glob`, `move_file`, `delete_file`: the path, relative to the project root when inside it (`./.env` and `src/../.env` both give `.env`), otherwise the absolute path.
- `fetch_url`: the URL.
- Other tools have no subject, so only a rule without `pattern` applies.

A project file can come from a cloned repo, so its `allow` rules do nothing until you trust them: `/permissions` lists them, `/permissions trust` activates the current ones (kept in `~/.sophi/permissions-trust.json`). Project `deny` and `ask` rules always apply. When a prompt can offer it, answering `a` (always) appends a project `allow` rule for exactly that command or path to `.sophi/permissions.json`, shows it first, and trusts it. A command with `*` in it is written escaped, so it only matches itself. A malformed rules file stops startup with an error naming the file, because ignoring it would drop deny rules silently.

In the SDK, `RuntimeBuilder.permissionRules(projectRoot)` loads the same files, or `permissions(rules)` takes a `PermissionRules` you built.

Known limits of permission rules:

- Bash matching is textual. A command that can't be split (a heredoc, an unterminated quote), or whose command word is quoted, escaped or a variable (`\rm`, `"rm"`, `r''m`, `$RM`, `{rm,x}`), is treated as unknown: denied when any `bash` deny rule exists. Wrappers and paths are not seen through: `/bin/rm`, `command rm`, `env rm`, `exec rm`, `sudo`, `xargs`, `bash -c` and `eval` must be named in the patterns.
- A bash part with an output redirect, or with a `NAME=value` prefix (`LD_PRELOAD=x git status`), is never allowed by a rule; it goes to the normal confirmation. Deny rules still see the command after the prefix. Redirects to `/dev/null` and `>&N` don't count, so they don't block an allow rule.
- `~` is not expanded in rule patterns; write absolute paths.
- Path subjects resolve symlinks and the on-disk case, so a path is matched as its real path. Patterns for paths outside the project must use the real path: on macOS `/private/etc/*`, not `/etc/*` (same for `/tmp` and `/var`). A symlink to a missing file can't be checked, so it fails closed.
- URL subjects are normalized (scheme and host lowercase, no userinfo or default port). Only the requested URL is checked, not its redirects.
- In `mcp-serve` an ask rule refuses the call, since nobody is there to answer.
- A scheduled daemon takes its project rules from the directory it was started in.
- sophi-web doesn't apply rules yet.
- Rules are not a sandbox: the real containment is a sandboxed backend.

**Sessions.** Each session is a JSONL file under `~/.sophi/sessions` that only grows: a save appends the entries that are new or changed, so saving a long session costs the same as a short one. A crash mid-write loses at most the last, partial line. The checked-out entry is saved too, so `--session <id>` resumes where `/checkout` left you, not at the last entry.

**Settings.** `/settings` opens a menu of every `sophi` start flag: ↑↓ moves, Enter flips an on/off setting, steps through a choice or edits a value in place, `d` resets one to its default, Esc closes. Each change is saved to `~/.sophi/settings.json` and used as that flag's default from then on; a flag you type still wins for that run (every on/off flag has a `--no-…` opposite for that), and each row says whether its value came from a flag, the file or the default. The sandbox, network, Docker image and auto mode change the running session at once; the rest are marked "(next start)". API keys, `--session` and `--god-mode` are never saved, and a value in the file that doesn't fit is skipped with a warning rather than stopping `sophi`. Without a terminal, `/settings` lists them, and `/settings <key> <value>` sets one.

**Sandbox.** `bash` runs unsandboxed by default. `--sandbox seatbelt` (or the `sandbox` setting) runs each command in macOS's sandbox: it can read anywhere but write only to the workspace, temp folders and tool caches. `--sandbox docker` runs commands in one container per session that shares only the workspace (mounted at the same path), with all capabilities dropped and CPU, memory and process limits; `--docker-image` picks the image (default `buildpack-deps:bookworm-scm`). `--no-network` blocks the network in either, name lookups included. Under seatbelt, commands can't reach Docker's socket either, since a container they started could see the whole disk. Switching to Docker starts the container straight away, so the first image pull happens then. A mode this machine can't run is refused with the reason. Path checks that flag commands reaching outside the workspace still apply in every mode. In the SDK, `SophiRuntime.bashSandbox.set(...)` switches it.

**Undo.** Before each tool round that changes files, the CLI snapshots the workspace into a shadow git repo under `~/.sophi/checkpoints` (your own repo is untouched). `/undo` and `/revert` show what they would restore and ask first. `move_file` and `delete_file` are undoable too: deletes go to `~/.sophi/trash`. Bash commands that reach outside the workspace can't be undone and are listed as warnings. In the SDK, `RuntimeBuilder.checkpoints()` (needs `builtinTools`) turns snapshots on and `SophiRuntime.fileRestorer` does the restoring.

The terminal redraws in place while a turn streams, instead of scrolling
line by line:

- **Live output** — streamed tokens and tool-call status update a single in-place region.
- **ESC to interrupt** — cancel a turn mid-stream; the partial reply is kept, tagged `[interrupted]`, and the prompt returns immediately.
- **Type while it works** — Enter sends a steer Sophi reads before its next step; Alt+Enter queues a follow-up for after it answers; ESC puts unsent text back at the prompt. Ctrl+T toggles the token view. A multi-line paste stays one message. Steering from the companion or the web comes later.
- **Multi-line input** — end a line with `\` to continue on the next line, same convention as bash.
- **Non-interactive fallback** — piped stdin or non-TTY output drops to plain line-mode automatically, so scripted usage still works.

**Subagents:** `--agents-dir` points at a directory of Markdown files, each
describing a subagent the CLI can delegate a task to mid-session:

```markdown
---
name: researcher
description: "Looks things up without touching any files"
allowedTools: [read_file, web_search]
---

You are a research assistant. Investigate the task and report findings
concisely. You cannot modify any files.
```

The frontmatter's `name` and `description` are what the main agent sees when
choosing which subagent to delegate to; the Markdown body becomes that
subagent's system prompt. Delegation nests up to 3 levels deep by default.

**Remote monitoring:** if `sophi-companion` is running, every `sophi-cli` session registers
with its embedded hub automatically — the companion's Sessions tab shows it live, and you can
send it a message or answer its confirmation prompts from there. No companion running, or
`--no-remote` passed: behaves exactly as before, no difference. See ADR-023.

**Good for:** local dev-tool style usage, exploring the agent loop, quick
one-off tasks — same category as a REPL.

---

## 🌐 Use case 2: Agent-over-HTTP (`sophi-web`)

Run SoPhi as a Spring Boot service so a frontend, another service, or a
mobile app can talk to it over REST/SSE.

```bash
mvn -pl sophi-web -am spring-boot:run
```

Endpoints (`AgentController`, base path `/api`):

```
POST /api/sessions?title=...          create a session -> {id, entryCount, lastModified}
GET  /api/sessions                    list sessions
POST /api/sessions/{id}/turn          { "input": "..." } -> { sessionId, reply }
GET  /api/sessions/{id}/stream?input=...   Server-Sent Events, one event per token
POST /api/sessions/{id}/feedback      { "polarity": "positive|negative", "reason"?, "entryIndex"? } -> rate a reply
```

Example:

```bash
curl -X POST localhost:8080/api/sessions
curl -X POST localhost:8080/api/sessions/<id>/turn \
  -H 'Content-Type: application/json' -d '{"input": "hello"}'
curl -N "localhost:8080/api/sessions/<id>/stream?input=hello"   # SSE stream
```

**Good for:** powering a chat UI, a Slack/Discord bot, or any client that
needs the agent behind a network boundary rather than in-process.

---

## 🛠️ Use case 3: Embedding SoPhi in your own JVM app (`sophi-sdk`)

Use the `RuntimeBuilder` DSL to wire up a runtime with your own tools and
plugins, no CLI or HTTP layer required.

```kotlin
import dev.sophi.sdk.Sophi
import dev.sophi.ai.providers.ClaudeProvider
import dev.sophi.infra.MetricsPlugin

val runtime = Sophi.runtime {
    provider = ClaudeProvider(chatModel)     // any LLMProvider
    model = "claude-sonnet-4-5"
    systemPrompt = "You are a build assistant."

    tool(MyCustomTool())                     // implement dev.sophi.core.tools.Tool
    grants(setOf("read_file"))               // tool names this runtime may use without confirmation
    plugin(MetricsPlugin(meterRegistry))
}

val sessionId = runtime.newSession(title = "release-check")
val reply = runtime.turn(sessionId, "What changed since the last tag?")
```

`provider` accepts any `LLMProvider` — for a local model instead of Claude, use
`dev.sophi.ai.providers.buildOpenAiCompatProvider` (from `sophi-ai`):

```kotlin
import dev.sophi.ai.providers.buildOpenAiCompatProvider

val runtime = Sophi.runtime {
    provider = buildOpenAiCompatProvider(
        baseUrl = "http://localhost:11434/v1", // Ollama; vLLM is typically :8000/v1
        apiKey = null,                          // no-auth mode for local servers
        model = "qwen2.5:7b"
    )
    systemPrompt = "You are a build assistant."
}
```

A custom tool is just:

```kotlin
class MyCustomTool : dev.sophi.core.tools.Tool {
    override val name = "my_tool"
    override val description = "Does something useful"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override suspend fun execute(argumentsJson: String): String = "result"
}
```

**Good for:** bolting an agent onto an existing JVM app (CI tool, internal
dashboard, batch job) without standing up a separate service.

---

## 🖥️ Use case 4: Desktop tray companion (`sophi-companion`)

`sophi-companion` — the OS tray / menu-bar desktop app embedding `sophi-sdk`
in-process, with Chat, Sessions, MCP, Goals, and Settings tabs — is now a
commercial, direct-sale product and no longer developed in this repo. It
still shares `sophi-cli`'s full tool surface and talks to CLI sessions over
the same open `sophi-hub` protocol described above; only the companion
app's own source has moved.

The last open-source snapshot is preserved at the
[`companion-last-oss`](../../tree/companion-last-oss/sophi-companion) tag
for anyone who wants to build or fork it as it stood before the move.

**Good for:** keeping an agent one click away while you work — background goals,
long-running sessions, and OS notifications, without a terminal or a browser tab.

---

## 🧰 Cross-cutting: skills and plugins

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
`sophi-infra` ships two ready to use:

- `MetricsPlugin(meterRegistry)` — emits Micrometer counters for turns started/completed/errored
- `BudgetTracker` — a standalone tracker (not a `SophiPlugin`) that throws `BudgetExceededException` once a token budget is exceeded; call `record()`/`used()` directly rather than registering it

Tool permissioning is no longer a plugin — `AgentLoop`'s `riskLevel`/`ConfirmationPolicy`/`grants` mechanism (ADR-016) is the one gate; see `RuntimeBuilder.grants(names)` below for the SDK-embedding equivalent of the old allowlist.

Register plugins either manually (`RuntimeBuilder.plugin(...)` /
`PluginRegistry.register(...)`) or via JVM `ServiceLoader` discovery
(`PluginRegistry().discover()`).

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

---

## 🎓 Cross-cutting: learning

SoPhi observes its own sessions and gets better at your project over time.
All three surfaces (CLI, web, SDK) wire this in automatically; everything
lives in plain JSONL files under `~/.sophi/learning/`, and learning is
strictly best-effort — it can never fail or slow down a turn.

The loop, end to end:

1. **Capture** — tool calls and session outcomes are recorded via lifecycle
   hooks; tools with a bad recent track record earn a reliability warning in
   the system prompt.
2. **Distill** — one LLM self-evaluation at session end judges the outcome
   (success / partial / failure) and distills deduplicated, reusable
   *lessons* that are recalled into future sessions' system prompts.
3. **Align** — rate replies with `/good` and `/bad [reason]` (or the web
   feedback endpoint); the evaluator also detects implicit signals like
   corrections, each backed by a verbatim quote. Preferences steer future
   behavior through the same lesson pipeline.
4. **Export** — `sophi export` turns the labeled history into fine-tuning
   datasets: chat-format `sft.jsonl` from success-judged sessions, TRL
   `dpo.jsonl` pairs from bad→good retries, plus a provenance
   `manifest.json`. Redaction of secrets/PII is on by default; output is
   deterministic and deduplicated.

Everything the agent has learned is inspectable and revocable:

```bash
sophi lessons list [--all] [--scope <dir>]   # what it believes about this project
sophi lessons archive <id>                   # retract a lesson (it may be learned again)
sophi lessons forget <id>                    # retract it for good: it is never learned again
sophi feedback list [--scope <dir>]          # your ratings + detected signals
sophi feedback delete <id>                   # revoke feedback
sophi export --out ./dataset   # build SFT/DPO training files
```

Training itself is out of scope by design — the datasets are the durable
asset, and they work with TRL, axolotl, unsloth, or llama-factory as-is.

---

## 🏛️ Cross-cutting: memory (Jane's Theory)

Where learning remembers *how to behave*, memory remembers *your world*:
who's in it, what happened, and how it connects — across sessions. It's
organized as a memory palace: the leverage is at **encoding time**, not
retrieval time. Opt-in and experimental:

```bash
# Local server with a nomic-embed model loaded: picked up automatically
sophi --memory

# Or name the embeddings model — any OpenAI-compatible embeddings endpoint works
sophi --memory --embedding-model nomic-embed-text --embedding-dimensions 768
```

How it works, per turn:

1. **Encode (async, after the turn)** — one cheap LLM verdict decides what
   deserves remembering, how strongly (emotional weight is the largest
   factor), in which of five rooms (entities, tasks, episodes, knowledge,
   narrative), and connected to what causal story. Most turns store nothing.
2. **Recall (before the turn, no LLM call)** — the query is embedded, routed
   to the most relevant rooms, scored by semantic match × decayed importance
   × your profile, then expanded along causal threads — the agent gets the
   story, not just the fact. Stale or third-party memories are injected with
   a `VERIFY` marker so the agent checks instead of asserting.
3. **Forget (a feature, not a failure)** — each room has its own decay
   half-life (episodes fade in days, life narratives last a year), and a
   consolidation "sleep" cycle merges duplicates and prunes what faded.

Sensitive topics (health, finances, relationships) are tier-guarded: never
volunteered, only surfaced when you raise the topic, and every access is
audited. Storage is an embedded ArcadeDB database (document+graph+vector)
under `~/.sophi/memory/` (user-global — your life isn't per-project), and
like learning it is strictly best-effort: memory can never fail or slow down
a turn.

Everything remembered is inspectable and truly deletable:

```bash
sophi memory list [--room tasks]    # browse what it remembers, by room
sophi memory show <id>              # one memory in full
sophi memory threads                # causal story lines
sophi memory profile                # what it believes about you (confirm/correct/delete)
sophi memory why                    # why did it bring that up?
sophi memory forget <id>            # hard delete — shows impact first, provably gone
sophi memory consolidate            # run the sleep cycle now
sophi memory reset                  # wipe everything
```

"Provably gone" is literal: deletion is a compacting rewrite of every store
file, the causal chain is re-linked around the gap, and the regression suite
greps every file on disk to assert zero traces.

---

## 🎯 Choosing a use case

| You want to... | Reach for |
|---|---|
| Poke at the agent yourself, or script one-off tasks | **`sophi-cli`** |
| Let a frontend, bot, or service talk to the agent | **`sophi-web`** |
| Add agent capability inside an existing JVM app | **`sophi-sdk`** |
| Keep the agent in your menu bar, working in the background | **`sophi-companion`** *(commercial — see Use case 4)* |

They all share the same `sophi-core` agent loop and session format, so
switching between them later doesn't change how sessions or tools work —
start small, grow into the rest.

---

## 🤝 Contributing

SoPhi is open to contributions from researchers, developers, and ecosystem builders. We welcome:

- 🧪 **Research & experimentation** — new memory techniques, learning approaches, LLM providers
- ✨ **Features & improvements** — new tools, plugins, and deployment modes
- 🐛 **Bug fixes** — correctness issues, performance improvements
- 📚 **Documentation** — guides, examples, architecture clarifications

**To get started:**

1. Read [`CONTRIBUTING.md`](CONTRIBUTING.md) for workflow and expectations
2. Check [`GOVERNANCE.md`](GOVERNANCE.md) to understand decision-making and stability commitments
3. Follow our [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) — we're committed to a welcoming community

**Quick checklist before you submit a PR:**

- [ ] Tests pass (`mvn test`)
- [ ] Changelog entry added to `CHANGELOG.md` under `[Unreleased]`
- [ ] Clear commit messages (imperative mood, reference issues)
- [ ] No debug logging or console noise

**Questions?** Open an [issue](https://github.com/shukriev/SoPhi/issues) or [discussion](https://github.com/shukriev/SoPhi/discussions).
