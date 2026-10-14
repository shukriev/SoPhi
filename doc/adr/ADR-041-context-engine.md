# ADR-041: Context engine on a shared, lock-elected ArcadeDB store

**Date:** 2026-10-12
**Status:** Accepted — M0–M3 implemented; live E1/E5 run on a small local model (hint gate ratio raised to 1.25, see RESULTS.md); frontier-model runs and the live cache check pending

## Context

Sophi finds code and notes only by grepping blind: no project discovery, no instruction files,
no index. On 31 labelled questions over the SoPhi and companion repos, a grep-style search put a
relevant file in the top 10 for 52% of them (MRR 0.22). A context engine has to serve both
`sophi-cli` and `sophi-companion`, share state when both run on one machine, and work when either
runs alone — and the user wants one store, not a second database next to ArcadeDB.

ArcadeDB allows one open instance per database, which is why `RuntimeBuilder` disables memory in a
second process and `sophi-versioning` opens and closes per operation. ADR-026 dropped a shared-server
design because remote vector search seemed impossible; that turned out to be a parser quirk (ADR-026
addendum).

## Decision

1. **One store, shared by election.** The first process to lock `~/.sophi/db/host.lock` runs an
   ArcadeDB server on 127.0.0.1 and uses the database in-process; others read `host.json` and use
   `RemoteArcadeStore`. The lock is the source of truth: a client whose call fails first reuses an
   already re-elected store, then tries the lock (becoming host if it is free), and rethrows only
   when the lock is held and `host.json` names its own live host. A partially failed takeover rolls
   back. A client built for a different store schema refuses to attach.
2. **Server credentials.** The server password is generated once per server root into a `secret`
   file (created 0600 before writing, tightened if found loose, regenerated if blank); it is not
   stored in `host.json`.
3. **SQL primitives in `sophi-store`** (`fullText`, `ensureFullTextIndex`, `ensureVectorIndex`,
   `insertVertex`, `nearestVectors`, `query`, `command`) over a shared `SqlArcadeStore`, so embedded
   and remote stores behave the same — one contract test suite runs against both.
   `nearestVectors` scores are cosine distance (lower is closer), identical embedded and remote.
4. **Lucene is back on the classpath** (FULL_TEXT needs it) and the server/network jars are added:
   the companion bundle grew from 251M to 267M.
5. **Ranking is ours, not the database's.** ArcadeDB's `$score` counts matched terms; the engine
   computes BM25 over its own identifier-splitting, stemming tokenizer. In a bake-off with identical
   preprocessing, ArcadeDB candidates + Kotlin BM25, SQLite FTS5 and Lucene all scored hit@10 0.90 /
   MRR 0.80.
6. **M1 — the lexical engine (`sophi-context`).** Documents-only schema (project, file, chunk, term,
   request, meta); vertices and edges arrive with M5. The host indexes: other processes write
   `IndexRequest` documents that the host drains on its single writer thread, so only one process ever
   writes the index. Projects are registered per working directory (a git repo root, or a folder with
   an instruction file; never `$HOME`, an ancestor of `$HOME`, or `/`; a folder inside a registered
   project is refused, registering a parent replaces its children); `isGlobal` is reserved (global
   search comes with the companion, M3). Enumeration skips build/vendor directories, symlinks, secret files, binaries and
   files over 1 MB; lockfiles and minified files are indexed as `generated` (metadata only). Scans are
   cancellable and an unready store answers `index not ready — use grep/glob/read` instead of failing a
   turn. Tools: `search_context`, `read_context`, `project_map`; CLI: `sophi context add|list|remove|reset|eval`.
   Measured with `sophi context eval` (evals/context/RESULTS.md): on the 31 handwritten queries hit@10
   0.97, MRR 0.80 (grep MRR 0.12); on 258 git-mined commit queries engine MRR 0.64 vs grep 0.26 (2.4x).
   Chosen weights: k1 1.2, b 0.5, name boost 1.5, at most 3 hits per directory, role priors
   SOURCE/INSTRUCTION 1.0, DOC/CONFIG 0.9, TEST 0.6, GENERATED 0.2. The no-priors BM25 baseline
   scores 0.94 / 0.79 on today's repos, so the priors and the k1/b choice earn the margin.
7. **M2 — placement, caching, orientation and hint.** A `ContextContributor` declares a `Placement`.
   `STABLE` text goes into the system prompt; `VOLATILE` text is wrapped in `<context>…</context>` and
   prefixed to the current turn's user message in the request only (never persisted, so the session
   keeps the raw input). It is not a SYSTEM message because Spring AI hoists SYSTEM messages into
   Anthropic's system field, which would change the cached prefix every turn; it is not a separate
   USER message because strict-alternation chat templates reject two USER turns in a row.
   `MemoryPlugin`, `LearningPlugin` and `SiteSkillRecallPlugin` are `VOLATILE`; `sophi-web` and
   `ScheduleEngine` still use `collectContext` (system prompt), a follow-up. With the system prompt now
   byte-identical across turns (`RuntimeBuilderContextTest`; caveat: in a project not yet indexed, orientation may be
   absent or "map pending" for the first turns and then becomes stable; the last good render is reused if the store fails), Anthropic prompt caching
   (`SYSTEM_AND_TOOLS`) is on for every request; `TokenUsage.inputTokens` is the total prompt including
   cache read/write and `cacheReadTokens` is reported; `SOPHI_USAGE_LOG` writes a JSONL of numbers
   (kind `turn`/`summarise`). Orientation (STABLE) = `~/.sophi/instructions.md` (at most half the budget,
   symlink allowed for the global file only), root `AGENTS.md` / `CLAUDE.md` / `.sophi/instructions.md` framed as project
   conventions, nested instruction files listed, and a deterministic sorted project map; budget 8% of
   the window clamped to 600–4,000 tokens, over-budget text keeps its head plus a truncation marker, and
   the first turn waits at most 1 s for the engine. The hint (VOLATILE) is at most 3 lines of
   `path:line — label — summary`, no file content beyond a one-line summary or snippet head, repo-derived text sanitised (`‹ ›`), budget
   min(1% of window, 300) tokens, and fires only with >= 3 content terms and top score >= 10 (plus
   top1/top2 ratio >= 1.25, or an identifier in the message). minScore comes from the offline E1 grid
   (`evals/context/RESULTS.md`, "Hint gate results"); the ratio was 1.0 until the live E1 negatives showed it
   firing on 6 of 10 chit-chat messages and was raised to 1.25 (none fire; queries.tsv coverage 0.516 /
   precision 1.000, commits.tsv 0.574 / 0.784 — RESULTS.md, "Live E1/E5"). Limit: the absolute floor does not fire
   on tiny repos. Knobs: `SOPHI_CONTEXT_HINT`, `SOPHI_CONTEXT_ORIENTATION`, `SOPHI_USAGE_LOG`.
   Pending: live E1/E5 and the live cache check (`evals/context/EXPERIMENTS.md`), deferred to the end
   of the work.
8. **M3 — session scope and the management surface.** A session is bound to at most one primary project
   through `ProjectOf = (sessionId) -> projectId?` (CLI: the project containing the working directory;
   companion: the chat's project chip, none by default). `ContextScope.searchIds(primary, projects)` gives the
   search scope: the primary project plus every project flagged global, deduplicated and sorted by root.
   `search_context`, `read_context` and `project_map` use that scope; orientation (STABLE) is rendered for the
   primary project only, and its store-failure fallback is keyed on the primary project so a session never
   serves another project's last render. `RuntimeBuilder.context(engine, owned, project: ProjectOf)` replaced
   the `scope: () -> List<String>` form (breaking for SDK users). `ContextEngine` gained `setGlobal`,
   `unavailableReason`, `status(): IndexStatus` (role host/client, per-project info, database disk size) and
   per-project scan progress (`scanned`/`scanTotal`, cleared when a scan ends, is cancelled or the root is
   missing). `IndexReset`/`ResetResult` (`Done`, `InUse`, `OtherVersion`) moved from the CLI into
   `sophi-context` as a library call: it moves the database aside only when no process is hosting it.
   CLI: `sophi context global <id|dir> on|off`, and `list` marks global projects. The companion owns one
   engine for the whole app (`ContextEngineHolder`, handed to the runtime with `owned = false` so settings
   rebuilds never close it; a reset swaps in a fresh engine and bumps a generation that makes the UI rebuild
   the runtime), remembers each chat's project in `~/.sophi/companion/session-projects.json`, and exposes
   Settings -> Projects (add/remove, "All chats" switch, rescan, status and scan progress, disk use,
   Reset index) plus a per-chat project chip for local chats. `ProjectSuggestions`
   (`~/.sophi/companion/project-suggestions.json`) offers the notes vault, the workspace and CLI folders seen
   twice; nothing is indexed without a click. Pending: live E1/E5 and the live cache check, as above; the
   M3 companion UI has not been run end to end by this record.

## Reasons

1. **Measured, not assumed.** The database choice was settled by a spike on the real 26.5.1 jar and
   a bake-off on our own repos: retrieval quality was identical across engines, so the deciding
   factors were "one store" and concurrency — and ArcadeDB host + remote passed a 20-second
   two-process test with zero errors and ~39 ms write-to-read freshness.
2. **SQLite was the strongest alternative** (WAL multi-process with no election, real BM25, 4×
   faster at 44k chunks) and was rejected only because it would split storage from Jane's Palace.
3. **Engine-level RPC was rejected** in favour of sharing the database: no new protocol to version,
   and memory/versioning can move onto the same server later, removing "memory disabled in the
   second process".
4. **Doubt resolves to rebuild.** The context database is a cache of files on disk; corruption or a
   schema change means recreate and reindex. (Memory, when it joins, is never auto-deleted.)

## Consequences

- `ArcadeHost.withStore` may run its block twice after a failover, so callers must pass idempotent blocks.
- Every consumer of `sophi-store` carries Lucene and the ArcadeDB server jars.
- `RemoteArcadeStore.putVector` is unsupported — vector writes happen on the host (inserts carry
  their vector; `putVector`'s index-put workaround needs the embedded API).
- Property names are interpolated unquoted into SQL, so reserved words (e.g. `by`) cannot be
  property names.
- Jane's Palace and versioning still open their own embedded databases until they migrate.
- Jane's Palace's recall explain line labels the store's distance as "semantic" (pre-existing,
  cosmetic; ranking uses its own cosine) — follow-up.
- Failover after a SIGKILL'd host measured 238–284 ms in `ArcadeHostTwoProcessTest` (3 runs).
