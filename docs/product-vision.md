# SoPhi core: Product Vision and Feature Map

*Draft, 2026-10-22. Drawn from the comparison of SoPhi with OpenCode (v2.1.0), pi (v1.3.0) and Hermes Agent, and from a check of every "SoPhi today" claim against the code on 2026-10-22.*
*Detailed designs are in the [harness gaps roadmap](superpowers/specs/2026-10-20-harness-gaps-roadmap-design.md); items it covers are tagged "Roadmap #n".*
*Items tagged **New** have no spec yet.*

## Vision

**The open, local-first agent harness.** One agent core that you can embed in your product, run as a service for your team, or use directly as a private agent on your own machine. It works well on a model running on your laptop, it never needs an API key, and it can prove what it did.

SoPhi core is the product. Everything else is built on it: the companion app is one product on top of it, and anyone can build their own through the SDK or the web API.

None of OpenCode, pi or Hermes does all of these together:
- runs well on a local model, with no setup;
- embeds in your own app (in-process on the JVM, over the web API from anything else);
- learns from experience;
- acts safely when no one is watching, and leaves a record.

**Principle:** local models come first. Every feature is built and tested against the reference local model (Qwen3-8B Q4_K_M) before any cloud provider.

## Who it's for

| Group | How they use SoPhi | What they need from core |
|---|---|---|
| **Integrators and product builders** | Embed SoPhi as the agent harness in their platform or product (the companion is our own example) | A stable, versioned SDK and web API; safe defaults; a record of what the agent did; their choice of model |
| **Teams and enterprises** | Run sophi-web to automate internal work | Logins, several users, their own safety rules, confirmations without a terminal, an audit trail |
| **A person on their own machine** | Use SoPhi directly, offline and private, for daily life, not only coding | No setup, no API key, data that stays local, undo when it gets something wrong |

Typical tasks, from the daily-life side but just as true for a team:
- organise files (bulk moves and deletes, so undo has to work);
- reminders from the calendar (calendar tools and cross-platform notifications already exist in `sophi-calendar` and `sophi-schedule/notify`);
- check infrastructure usage limits and raise alarms (connectors with stored credentials, scheduled checks that only speak up when a line is crossed);
- onboarding and offboarding (create and remove accounts across several systems: show the plan, approve each risky step, record exactly what happened).

**Connectors come from MCP, not from core.** Core ships no connectors to outside systems (cloud consoles, identity providers, SaaS admin APIs). They come from existing MCP servers; core provides what makes using them safe: stored credentials, MCP OAuth, permission rules, plan-first approval and the audit log. The daily-ops eval runs against a fake MCP server with a test account.

These tasks are personal operations work, and they need the same things an enterprise needs. That is why one set of foundations can serve all three groups.

---

## 1. Three foundations (what every group needs)

The feature map below is ranked by these, not by what competitors have.

1. **Works locally with no setup.** Default to a local model, find the server, detect the context window, cope with how small models write tool calls. (Roadmap #1, #8)
2. **Safety you can prove.** Undo for file changes, allow/deny/ask rules, a real sandbox, and an **audit log** of every tool call and approval. Core has no audit log today; it is a foundation, not an extra. (Roadmap #3, #6, #7; audit log **New**)
3. **One runtime everywhere.** The SDK, sophi-web and the CLI get the same features and the same safety. The web API gets a version number and an auth hook that a paid module can fill in later (see section 5). (Roadmap #2; API versioning and auth hook **New**)

## 2. Where SoPhi leads, and where it really stands

| Feature | SoPhi today | Best of the others | Next step |
|---|---|---|---|
| Embedding in an app | In-process Kotlin DSL `Sophi.runtime { }`; companion built on it | pi has a TypeScript SDK; OpenCode only has HTTP | Version the SDK and web API and say what is stable; add RPC (Roadmap #12) |
| Planning | `decompose_goal`: a dependency graph of steps, critics, escalation, replanning | OpenCode has a read-only plan agent | Show the live plan in the CLI and the web, not only in the companion |
| Loop guards | Catches repeated identical calls, runs of failures, widening searches, and announcing an action without calling a tool | Others have step limits or retries | Tune the thresholds for small local models, which loop more |
| Scheduling and notifications | Cron, one-off and goal runs; native notifications on macOS and other platforms | Hermes has `cronjob_manage` | Use it for the daily tasks above; add "only tell me when something crosses a line" checks |
| Codebase index | BM25 plus a symbol graph | None of them has one | Make it the default way to search on local models, to save context |
| Compaction | Two levels; the summary records files touched and commands run | pi chains summaries and summarises the branch you leave | **New:** build each summary on the last one, and summarise a branch when you leave it |
| Testing its own changes | Tournaments, versioning, and self-PRs gated by evals | None of them | Make the local-model eval the gate (Roadmap #1) |
| MCP server | Exposes SoPhi's own tools | None of them | Keep as is |
| Memory | Memory palace with graph and vector search. **Measured:** the baseline store audit found a 91% miss rate and 50% precision, caused by the significance gate capping neutral facts below the save threshold | Hermes uses markdown files | Fix coverage before calling it a lead; then local embeddings (Roadmap #1) |
| Learning | Tool reliability stats, lessons, `/good` and `/bad`, SFT/DPO export. **Measured:** the delayed-credit memory RL work was shelved as premature | Hermes has background review | Provenance and outcome labels, then our own fine-tuned agent model (fine-tuning spec) |

## 3. Feature map

Ranked by the foundations. Items that serve only coding users sit lower than before.

### A. Works locally with no setup (foundation 1)

| Feature | Seen in | SoPhi today | What we build | Priority |
|---|---|---|---|---|
| **Local server works with no setup** | none of them do this well | Every command defaults `--provider` to `claude` and `--model` to the stale `claude-3-5-sonnet-20241022` (`SophiCli.kt:61`); the context window must be given by hand | Default to `local`, which finds llama.cpp, LM Studio or Ollama and detects the context window | **P0**, Roadmap #1 |
| **Parsing for small models** | Hermes (partly) | Reads `reasoning` fields only; no `<think>` parsing, no recovery of tool calls written as text | Parse `<think>`, recover tool calls written as text, repair broken JSON | **P0**, Roadmap #1 |
| **Load tools on demand (`tool_search`)** | pi, Hermes | Every tool is sent on every turn | Deferred tools, so small context windows stay usable | **P0**, Roadmap #8 |
| `sophi setup local` (optional) | Companion (downloads llama.cpp + model) | Companion only | **New**: an optional command that downloads llama.cpp and the reference model for people with no model server. Not required: people can equally install Ollama or LM Studio themselves, or use the Claude API with a key | P1 |
| Fallback chain | Hermes | None | Local server fails → second local model → cloud only if the user opted in | **New**, P2 |
| Switch provider mid-session | pi | Not possible | `/model` switch that drops thinking blocks at the switch | **New**, P3 |
| Model catalog and cost | OpenCode, pi | `BudgetTracker` exists but only its own test uses it | Curated catalog; local models show tokens/s, cloud models show dollars | P3, Roadmap #9 |
| Claude tool calling | — | Broken in streaming | Fix it as an optional cloud provider | P3, Roadmap #14 |
| Subscription login | pi, OpenCode | None | ChatGPT and Copilot only, **never Claude** | Last, Roadmap #15 |

### B. Safety you can prove (foundation 2)

| Feature | Seen in | SoPhi today | What we build | Priority |
|---|---|---|---|---|
| **Checkpoints and undo** | OpenCode, Hermes (shadow git) | No file undo | Shadow-git checkpoints for the workspace (Roadmap #3), plus undo for **any file path the agent touches**: deleted files go to a SoPhi trash folder and every move or rename is recorded so `/undo` can reverse it, also outside the workspace (e.g. `~/Downloads`). Things that aren't files (accounts, cloud resources) are covered by approval, not undo. Moved up: organising files is a core daily task | **P0**, Roadmap #3 (widened) |
| **Audit log** | Hermes (partly) | None in core | **New**: one global append-only JSONL log, separate from the session log, with one entry per tool call, approval or denial: time, session, actor id, tool, arguments (secrets redacted), the rule that decided it, and the result. A sink interface so `sophi-enterprise` can add retention and export. Plain append-only for now; a hash chain is added when a customer needs tamper evidence | **P0** |
| **Allow / deny / ask rules** | OpenCode | Risk tiers plus a classifier | Wildcard rules per tool, path and command | **P1**, Roadmap #6 |
| **Splitting compound shell commands** | OpenCode (tree-sitter) | Heuristics | Split `a && b \| c` and check each part | **P1**, Roadmap #6 |
| **Real sandbox** | Hermes (Docker, Modal, SSH…) | Commands run on the host; `BashWorkspace` calls itself "a heuristic, not a sandbox" | Docker and macOS Seatbelt `ExecBackend`s, as in Roadmap #7; remote backends later. Personal file organising runs on the host behind rules and undo, not in the sandbox | **P1**, Roadmap #7 |
| **Plan first, then act** | OpenCode (plan agent) | `decompose_goal` plans, but doesn't stop for approval of the whole plan | **New**: a dry-run mode that shows every step and its risk before anything runs. Approving the plan pre-approves exactly the listed calls (same tool, same arguments); any call outside the plan goes through the normal rules, and unattended runs stop on it. Needed for onboarding/offboarding | **P1** |
| **Stored credentials** | Hermes, OpenCode | None; keys come from environment variables | **New**: a credential store (OS keychain locally, pluggable for servers) so connectors never put secrets in prompts or logs | **P1** |
| Deny by default when unattended | Hermes | Each scheduled task lists the tools it may use | **New**: stop after 3 denials when no one is watching, and report back | P2 |
| Supply-chain checks | pi, Hermes | None | **New**: pin skills and plugins to exact commits; scan skills on install | P2, Roadmap #13 |
| LLM guardian | Hermes | An LLM classifier rates risk | **New**: a second review by the local model before high-risk actions, written to the audit log | P3 |

### C. One runtime everywhere (foundation 3)

| Feature | Seen in | SoPhi today | What we build | Priority |
|---|---|---|---|---|
| **sophi-web parity** | OpenCode (web, desktop) | `DENY_ALL` confirmations, no compactor, incomplete hooks, 3 test files | Same features as the CLI, confirmations over SSE | **P0**, Roadmap #2 |
| **Versioned SDK and web API** | pi (SDK), OpenCode (HTTP) | No stability promise; web endpoints unversioned (`/sessions/...`) | **New**: `/v1/` web API, a list of stable SDK types, and a changelog rule for breaking changes | **P1** |
| **Auth hook in sophi-web** | OpenCode | No auth | **New**: one interface for who is calling; the open default is a single local user or API key, the paid module adds SSO and roles. Sessions, rules, audit entries and stored credentials carry an actor id (and an optional tenant id) from day one, even when open web has one user, so the paid module never has to change core's data | **P1** |
| **Steer and follow-up** | pi, Hermes | ESC to interrupt only | Send a message during a run, or queue one for when it stops | **P1**, Roadmap #4 |
| Append-only sessions | pi (JSONL v3) | Whole file rewritten on every save | Append entries, plus entries that edit context | **P1**, Roadmap #5 |
| More hook events | pi (~40 events) | 5 hook points (`BEFORE/AFTER_TURN`, `BEFORE/AFTER_TOOL`, `ON_ERROR`) | Tool calls, provider calls, compaction, session lifecycle | P2, Roadmap #10 |
| MCP OAuth | OpenCode, pi, Hermes | stdio and HTTP without OAuth | **New**: OAuth for remote MCP servers; needed for infrastructure connectors | **P1** |
| RPC mode | pi | None | `sophi --mode rpc`, which the eval suite also uses | P2, Roadmap #12 |
| Common skill formats | OpenCode, pi | Own format only | **New**: also read `.agents`, `.claude` and agentskills.io skills | P2 |
| Test depth | OpenCode (~1,200 test files) | ~309 test files (not counting `.worktrees/`) | One contract test suite for every provider and every surface | Ongoing, Roadmap #13 |

### D. Tools

| Feature | Seen in | SoPhi today | What we build | Priority |
|---|---|---|---|---|
| Todo list and ask-the-user | OpenCode, Hermes | None | `todo` and `ask_user` tools | P2, Roadmap #11 |
| Background shell | OpenCode, Hermes | Commands block the turn | Run commands in the background, read output later | P2, Roadmap #11 |
| Search past sessions | Hermes | None | **New**: full-text search over past sessions, as a tool | P2 |
| Multi-file `apply_patch` | OpenCode, Hermes | Only single edits | `apply_patch` with fuzzy matching | P2, Roadmap #11 |
| Browser tools in core | Hermes | Companion has Playwright; core has none | **New**: move them into an optional core module | P3 |
| CodeMode (model writes code that calls tools) | OpenCode, pi, Hermes | None | **New**: sandboxed script tool (e.g. GraalJS) to chain calls in one step | P3, needs a spike |
| Worktree per subagent | Hermes, OpenCode | Subagents share the working tree | **New**: optional own git worktree per subagent | P3 |
| Background, resumable subagents | OpenCode, Hermes | Sequential, up to 3 levels deep | **New** | P3 |
| Export and share a session | pi | Only training data export | **New**: `sophi export --html` | P3 |
| IDE integration (ACP) | OpenCode, Hermes | None | **New**: ACP adapter | P3 |
| Install plugins from git | pi | ServiceLoader only | **New**: `sophi install git:` | P3 |

### E. Housekeeping found in the check

- Replace the stale default model id; it goes away anyway when `local` becomes the default (#1).
- Clean up `.worktrees/` (865 test files of old worktrees in the repo folder) and the stray `hs_err_pid*.log` files.

## 4. What we deliberately won't copy

- **Thousands of models** (OpenCode). Breadth doesn't help local-first users. We support a curated catalog well.
- **About 27 messaging platforms** (Hermes). These belong in products built on core, such as the companion; Discord first.
- **No permission system** (pi). SoPhi stays safe by default.
- **Claude subscription login.** Anthropic's terms forbid it.
- **A huge codebase** (Hermes, ~1M lines). Small modules; add one only when a real consumer needs it.
- **A hosted SoPhi service.** Heavy operations work for one maintainer, and it goes against local-first.

## 5. How SoPhi earns

SoPhi core, the SDK and the web API stay open under Apache 2.0. People embed SoPhi because it is open, so we don't close what they build on. Money comes from organisations and from products built on top.

| Stage | What earns | Trigger |
|---|---|---|
| **Now** | The companion (private repo, direct sale). Paid support contracts for anyone running SoPhi in production: response time, upgrade help, connector help. Support needs no new code. | Already true |
| **Next** | `sophi-enterprise`, a paid module in a private repo like the companion: SSO and logins, roles, multiple tenants, an admin console, audit retention and export. It plugs into the open auth hook and audit log (section 3B and 3C), so core doesn't change. | The first real company that asks for logins, roles or audit retention, or for a support contract. Not built before then. |
| **Later** | Paid verified connectors and skills in `sophi-hub`; the fine-tuned SoPhi agent model, open or licensed. | Enough users that a marketplace or a model has buyers |

The open core has to be good enough on its own that a personal user never misses the paid parts. A personal user never needs SSO, roles or multiple tenants, so the boundary costs them nothing.

## 6. Order

1. **P0:** local models first (#1) → `tool_search` (#8) → checkpoints and undo (#3) → audit log (**New**) → web parity (#2)
2. **P1:** permission rules (#6) → stored credentials and MCP OAuth → plan-first dry run → versioned API and auth hook → steering (#4) → append-only sessions (#5) → Docker sandbox (#7)
3. **P2:** tools (#11) → hooks (#10) → RPC (#12) → skill formats, session search, unattended deny-by-default
4. **P3 and later:** catalog and cost (#9), the Claude fix (#14), ChatGPT and Copilot sign-in last (#15)
5. **Separately:** memory coverage fix; fine-tuning our own agent model (SP1–SP4), starting after #1

## 7. How we know it's working

| Signal | Target |
|---|---|
| Agent eval pass rate on Qwen3-8B | Rises every release; never drops by more than 3 points. Run by hand on the maintainer's Apple Silicon machine before each tag until a CI runner that can host the model exists |
| Valid tool calls on the local model | ≥ 98% |
| Unsafe actions in the safety eval | 0 |
| Every tool call and approval appears in the audit log | 100% in the safety eval |
| **New:** daily-ops eval (organise a folder, calendar reminder, usage alarm, offboard a test account on a fake MCP server) on the local model, unattended | Passes, and every destructive step can be undone or was approved |
| Install to first completed task, local, no API key | < 10 minutes with Ollama or LM Studio already running; model download time not counted when using `sophi setup local` |
| Features available in the SDK, the CLI and the web | All P0 and P1 |
| First paying customer for support or `sophi-enterprise` | Recorded when it happens; it is the trigger for section 5 "Next" |
