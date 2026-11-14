# The terminal agent (sophi-cli)

[← Back to the README](../README.md)


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
sophi --memory                # enable long-term memory (see docs/learning-and-memory.md)
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
/model [id]              show the model and its window, or switch to another one
/jobs [kill <id>]        list background bash jobs, or stop one
/search <words>          find past sessions that mention every word (the model has the same search as `session_search`)
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

**Guardian (opt-in).** With `guardian` on in `/settings` (or `--guardian` for one run), a destructive call about to run without your approval (an allow rule, a grant, auto mode or `--god-mode` let it through) is first shown to the model, which answers allow, ask or deny. Ask sends it to you as an ask rule would; deny refuses it and tells Sophi why. If the review fails or times out, you are asked. It never allows anything a rule or you refused, and never reviews a call you just approved. It uses the session's model unless `guardian-model` names another. Each verdict is in that call's audit entry as `guardian` (`deny: <reason>`, redacted). In the SDK, `RuntimeBuilder.guardian(model)`. Not yet applied to `sophi schedule` runs or `/plan`.

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
- `read_file`, `write_file`, `edit_file`, `apply_patch`, `grep`, `glob`, `move_file`, `delete_file`: the path, relative to the project root when inside it (`./.env` and `src/../.env` both give `.env`), otherwise the absolute path.
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

**Settings.** `/settings` opens a menu of every `sophi` start flag: ↑↓ moves, Enter flips an on/off setting, steps through a choice or edits a value in place, `d` resets one to its default, Esc closes. Each change is saved to `~/.sophi/settings.json` and used as that flag's default from then on; a flag you type still wins for that run (every on/off flag has a `--no-…` opposite for that), and each row says whether its value came from a flag, the file or the default. The sandbox, network, Docker image, auto mode, model and cost budget change the running session at once; the rest are marked "(next start)". API keys, `--session` and `--god-mode` are never saved, and a value in the file that doesn't fit is skipped with a warning rather than stopping `sophi`. Without a terminal, `/settings` lists them, and `/settings <key> <value>` sets one.

**Models and cost.** Each reply ends with a gray line showing the prompt size and the tokens the turn wrote. On a cloud provider it adds what the turn and the session cost: `claude-x · 12.3k in · 820 out · $0.0412 this turn · $0.31 session`. On a local model it shows the speed instead (`· 38 tok/s`). Prices, context windows and capabilities come from a [models.dev](https://models.dev) catalog. A snapshot ships with Sophi, and `sophi` refreshes it in the background at most once a day into `~/.sophi/models.json`; `--no-catalog-refresh` keeps it offline. Entries in `~/.sophi/models.local.json` (the same compact form) override it. A cloud model's window comes from the catalog unless you pass `--context-window-tokens`. A local model's window always comes from its server; the catalog gives only its base model's capabilities, and warns at start when that base model can't call tools. `sophi models <query>` looks a model up, and `sophi models --refresh` downloads the catalog now. `--max-cost-usd 5` (or the `max-cost-usd` setting) stops the session once its requests have cost $5, including `/goal`, `/plan`, subagents and compaction; the turn ends with what it did so far, and raising the budget lets the next turn run. The session total counts this run only. `/model <id>` switches the model from the next turn, within the same provider (a local server must list it), and the window follows the new model. In the SDK: `RuntimeBuilder.modelCatalog`, `priced`, `budget(maxUsd)`, then `SophiRuntime.costMeter` and `switchModel(model, window)`.

**Sandbox.** `bash` runs unsandboxed by default. `--sandbox seatbelt` (or the `sandbox` setting) runs each command in macOS's sandbox: it can read anywhere but write only to the workspace, temp folders and tool caches. `--sandbox docker` runs commands in one container per session that shares only the workspace (mounted at the same path), with all capabilities dropped and CPU, memory and process limits; `--docker-image` picks the image (default `buildpack-deps:bookworm-scm`). `--no-network` blocks the network in either, name lookups included. Under seatbelt, commands can't reach Docker's socket either, since a container they started could see the whole disk. Switching to Docker starts the container straight away, so the first image pull happens then. A mode this machine can't run is refused with the reason. Path checks that flag commands reaching outside the workspace still apply in every mode. In the SDK, `SophiRuntime.bashSandbox.set(...)` switches it.

**Questions.** When Sophi is blocked on a decision that's yours (a preference, a choice between approaches) it asks with `ask_user`: the question prints with numbered options; type a number or a reply and press Enter, or Esc to skip. Only in an interactive terminal; scheduled runs that ask time out after 4 minutes and carry on. In the SDK, `RuntimeBuilder.askUser(asker)`.

**Desktop.** In an interactive terminal Sophi can `notify` you when something long finishes (a macOS notification, `notify-send` on Linux, otherwise a terminal bell), read and write the clipboard (`clipboard_read` asks first unless auto mode allows it), and `open` an http(s) URL or an existing file in its default app. `open` won't launch programs: it opens only documents and media (web pages, PDFs, text, office files, images, audio, video); folders, executables, scripts, shortcut files and installers are refused. In the SDK, `RuntimeBuilder.desktop(desktop)`; a host passes its own `Desktop` to route these elsewhere.

**Todo list.** For a task with several steps the model keeps a checklist with the `todo` tool; the CLI prints it in gray under any turn that changed it. It is stored in the session and each turn shows the model its latest state, so it survives `/compact`, resume and `/checkout`. In the SDK, `currentTodos(session)` reads it.

**Background jobs.** `bash` with `background: true` starts a long-running command (a dev server, a watcher, a long build) and returns at once with a job id; the model reads its new output with `bash_output` (which can wait up to 120 s for more) and stops it with `bash_kill`. At most 8 run at once. `/jobs` lists them and `/jobs kill <id>` stops one. Every job is killed when Sophi exits. Output goes to `~/.sophi/jobs/<pid>/`. Under the Docker sandbox, background jobs are refused for now. In the SDK, `SophiRuntime.jobs`; `RuntimeBuilder.jobsHome` moves the logs.

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

**In your editor (ACP):** `sophi --mode acp` speaks the [Agent Client Protocol](https://agentclientprotocol.com)
(protocol version 1) on stdin/stdout, so Zed and other ACP clients can drive Sophi. In Zed's `settings.json`:

```json
{
  "agent_servers": {
    "Sophi": {
      "type": "custom",
      "command": "sophi",
      "args": ["--mode", "acp"]
    }
  }
}
```

Sophi works in the directory Zed starts it in (the project root) and asks before risky tool calls through
Zed's allow/reject prompt. Not supported yet: loading past sessions, images, MCP servers passed by the editor
(use the project's `.sophi/mcp.json`), and the editor's own file and terminal access (Sophi uses its own tools).

**Good for:** local dev-tool style usage, exploring the agent loop, quick
one-off tasks — same category as a REPL.

