# ADR-041: Context engine on a shared, lock-elected ArcadeDB store

**Date:** 2026-10-12
**Status:** Accepted — M0–M6 implemented (M6: manual check on the real install pending); frontier-model runs, the live cache check, the LLM-summary production path and an embeddings re-test pending

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

9. **M4 — session memory and compaction (`sophi-core`).** Compaction is rebuilt from four parts. `WorkingSet`
   is a pure function over persisted tool rounds (ADR-009): files read/written, searches, bash commands with
   exit codes (unknown when the output was stopped or truncated) and tool errors, so it needs no store, is never
   stale and is free on resume; `merge` joins an earlier set with a newer one. `Elider` replaces tool results
   older than the last 2 rounds (`COMPACTION_KEEP_RECENT_ROUNDS`) with `[elided: <tool> <key arg>, <N> lines — re-read
   if needed]` — no LLM call, call/result pairing intact — and `elideLargest` stubs the largest results at or after
   the turn start, the newest round included and file-reading tools first, until a target is met; compaction aims
   at 3/4 of the trigger (headroom, so the next round does not trip it again). Results the model has not seen
   yet get a "too large to show at once; re-read a smaller part or narrow the request" stub. `Summariser` makes the
   one LLM call (`reasoningEffort = "none"`) with a `CompactionMode`: `GENERIC` (the old free-text summary),
   `ELIDE` (generic summary, but elision first) or `STRUCTURED` (Goal, Decisions & findings, Files touched,
   Commands & outcomes, Errors & attempts, Open items; the LLM writes Goal, Decisions, Errors and Open items, code
   adds the exact file/command/error sections from the `WorkingSet`; budget 3% of the window clamped to 400–2,000
   tokens). A blank or failing summariser degrades to a capped truncated transcript, with the exact sections kept.
   In `ELIDE` and `STRUCTURED`, `AgentLoop.compactInPlace` elides first, summarises only if still over, and when
   there are too few rounds to cut (or a summary still leaves the context over) stubs the largest results instead
   of ending the turn with `ContextExhausted`; `GENERIC` keeps the old behaviour.
   **Template-safe placement.** A summary is never a non-leading SYSTEM message (some chat templates reject it).
   Mid-turn it is appended to the current turn's user message after a marker and replaced, not stacked, on a second
   compaction (the earlier summary is fed back into the new one). Cross-turn it is a leading USER ("Summary of the
   conversation so far: …") and ASSISTANT ("Understood — continuing from that summary.") pair carrying
   `_compacted` and `_workingSet` metadata; the next cross-turn compaction decodes that `_workingSet` and merges
   it with the rounds it now drops, so exact facts survive repeated compactions, and the kept tail always opens on a
   USER entry. Cross-turn compaction runs automatically at the end of a turn in every runtime built by
   `RuntimeBuilder`. The knob `SOPHI_COMPACTION` = `structured` (default) | `elide` |
   `generic`, normalised like the other knobs; internal. `SOPHI_USAGE_LOG` kinds are now `turn`, `summarise`
   and `elide` (`AgentLoop` reports an elision as a zero-token `elide` usage event).
   **E4 outcome** (RESULTS.md, "E4 compaction"): bonsai-27b on LM Studio, window 16k, 5 two-question prompts, one
   run each, three configs interleaved. Generic 3/5 (2 turns ended with `ContextExhausted` on an oversized
   parallel round), elide 5/5, structured 5/5; elide and structured both beat generic on success. Total tokens
   157,549 / 177,031 / 253,809, but the summariser never ran for elide or structured (elision alone sufficed), so
   they ran the same path and the gap is one long run. The default stays `structured`. E4 showed that elision and
   the oversized-round handling work; it did not exercise the structured summary live, so structured against
   generic summaries is unsettled until a longer-task run. Single runs, one small model, n=5 prompts of which 2 never compacted in any config (effective n=3, 2 decisive). The E4 prompts were single-turn, so only mid-turn compaction ran; the cross-turn path (USER/ASSISTANT summary pair, `_workingSet` merge) is covered by unit tests only.

10. **M5 — graph and semantics (rulings and outcome).** The graph is two
    *document* types, not vertices and edges (a deviation from spec §5.1): `Symbol {projectId, fileId, path,
    role, name, lname, kind, line}` and `Relation {projectId, fromFileId, toFileId, fromPath, toPath, kind
    IMPORTS|LINKS|TESTS}`, indexed on name/lname/projectId/fileId and fromFileId/toFileId/projectId. The context
    database is a rebuildable cache and File/Chunk are already documents; a lookup by indexed id is all
    `find_symbol` and `related` need, with no traversal. Spec §5.1's `Symbol.qualifiedName`/`signature` and the
    CONTAINS/MENTIONS edges are dropped in M5 (YAGNI; added when a tool needs them). `SCHEMA_VERSION` 2 makes
    every host rebuild.
    Extraction is regex (declarations from the existing patterns; imports per family; markdown
    `[[wikilinks]]` and relative `.md` links); resolution happens in the indexer, two-phase: a file's own edges in
    its transaction, and the whole project's after any add/remove (`Project.relationsAt` null = dirty), so a new
    file satisfies imports in files that did not change. Qualified names resolve by path suffix
    (`a.b.C` → `…/a/b/C.kt|.java`), relative imports by path, wikilinks by note name, TESTS by naming convention
    (FooTest/FooSpec, foo.test.ts, test_foo.py, foo_test.go); anything else is dropped. Embeddings are one
    `ChunkVec {id = chunkHash, emb}` vertex per distinct chunk, inserted once with its vector, embedded by the
    host in the background only when `SOPHI_CONTEXT_EMBED` names a model; the base URL must be loopback in
    M5 (a per-project "allow sending content to a remote provider" switch, spec §5.4, is deferred). Retrieval
    fuses BM25 with the 50 nearest chunk vectors by RRF (k = 60); any vector failure is lexical-only.
    Summaries are an indexing input: heuristic summary words or an LLM summary are added to a file's first
    chunk. Each of `find_symbol`/`related` (`SOPHI_CONTEXT_TOOLS`), embeddings and summaries is on by default
    only if E2 (offline) or E3 (live) justified it — see the outcome below.

    **Outcome** (RESULTS.md, "E2 vocabulary gap" and "E3 tool surface"). E2, offline, MRR on queries (31) / commits
    (258) / docs (37), bm25 baseline 0.76 / 0.62 / 0.58: heuristic summaries 0.76 / 0.62 / 0.58 (no movement, off);
    LLM summaries (bonsai-27b) 0.77 / 0.62 / 0.65, which passes the fixed rule (docs +0.07) but rests on about 3 of
    37 docs queries and costs about 55 minutes of offline summarising, and the production path (lazy, at query time)
    does not exist, so the default is off and a follow-up plan builds it (an `LLMProvider` into the host engine,
    `IndexRequest(kind="summarise")` for top-5 hits whose `summaryHash != contentHash`, the host drain calling
    `FileSummariser` and `applySummary`). That path reuses code already in the tree, which is not dead: `FileSummariser`,
    `LlmSummaries`, `Indexer.applySummary`, `IndexOptions.summaryTokens` and `Judgments.CONTEXT_SUMMARY`. ChunkVec orphan GC (vectors whose chunk hash no
    longer exists; ChunkVec is kept across rebuilds) is needed before embeddings default on; embeddings (nomic v1.5, 768 dims) 0.70-0.74 / 0.59-0.60 / 0.64-0.65,
    recorded as **inconclusive** and off: every run logged SEVERE `LSMVectorIndex FALLBACK: Built 0 active vector
    IDs`, and runs were not reproducible. Follow-up: verify the ChunkVec neighbours do not come from a degraded path,
    then re-test. Embeddings are experiment-only; a Finder-launched companion needs `launchctl setenv
    SOPHI_CONTEXT_EMBED …` (or a terminal launch) to try them. E3, bonsai-27b, 23 FILES questions: `all` 21,
    `core` 22, `none` 20 successes; `core` nominally beats `all` (one question where `all` timed out, +8% tokens: a tie
    in substance), `none` loses (+48% tokens). `find_symbol`/`related` were never called, so this set cannot judge
    them; the default `SOPHI_CONTEXT_TOOLS` stays `all` (no tier detection, frontier untested). `core` is fine for
    small local models; follow-up: a symbol/relationship question set. On by default, then: both new tools, lexical
    retrieval only, no summaries, no vectors. Findings to keep: `DROP TYPE ChunkVec UNSAFE` works on 26.5.1; with
    vectors on there is no distance floor, so "No matches" never appears; the query side does not check the stored
    embed model/dims (a cross-process mismatch goes unnoticed); a non-loopback embed URL is refused and the
    per-project remote switch stays deferred; the eval `.tsv` files are indexed and depress every variant
    (follow-up: exclude `evals/` from indexing).

11. **M6 — shared server for memory and versioning.**
    - **One host, several databases.** One `ArcadeHost` per root per process (`SharedArcadeHost` leases) serves `context`, `memory`, `memory-versions` and `versioning`. `register(database, version)` plus `host.json` `schemas` limit a mismatch to that database. `host.json` also carries the sentinel `schemaVersion: -1`, so every pre-M6 client refuses the host cleanly instead of attaching to `context` in the gap before the host has registered its other databases (Q1). An old-format `host.json` is checked against every database. A client re-reads `host.json` on `register`, so databases the host registers later are still checked (Q2).
    - **In-database marker (Q10).** On the first use of a database in a process, a single `SophiSchema` document holds the schema version. Absent: written. Equal: fine. A stored version newer than the process's is refused for that database only. An older one may be upgraded only by the host (the owner rebuilds, e.g. the context cache); a client is refused. This catches CLI/companion version skew that the `host.json` pre-check misses.
    - **Failover.** `FailoverArcadeStore` retries one call (or one transaction block) on the new host. Every memory/versioning write is an upsert, a delete, or delete+insert in one transaction, so a replay is safe.
    - **Vectors.** Memory vectors live in `MemoryVec`, inserted together with the vector (the same pattern as `ChunkVec`), because a remote client cannot set a vector property afterwards. `PalaceStore` adopts inline `Memory.embedding` vectors into `MemoryVec` on the host, tolerating a concurrent adopter. Remote `nearestVectors` without an index returns empty, like embedded; a client with `SOPHI_CONTEXT_EMBED` set and no `ChunkVec` index now falls back to lexical instead of failing, at the cost of one `schema:indexes` round-trip per remote search.
    - **One-time migration (Q11, Q3).** Host-only: copy, verify, write a marker, rename the old directory to `arcadedb.migrated-<ts>`; the old directory is never deleted. The marker field is `fromPath`. The target is cleared only if it is empty or holds a `MigrationInProgress` document this migration wrote before its first write. A non-empty target with neither is never touched: the result is `TargetNotEmpty`, nothing is renamed, memory proceeds on the live target and the legacy directory stays for the user. `TargetNotEmpty` never clears. A host or client that finds no legacy directory also writes the marker, so a later stray directory (an older build recreating `<home>/arcadedb`) is renamed aside, never copied over live data; a stray directory that is locked is skipped. A locked source, or a client that finds unmigrated data, disables the feature for that run with one line. The `SophiSchema` document is ignored by copy, verify and clear.
    - **Only the default homes move (Q12).** A home counts as default when its `toRealPath()` equals the default's, so a symlinked or differently-cased spelling of `~/.sophi/memory` goes shared too instead of staying embedded and making memories look gone. Other homes (tests, `--memory-home`, the skills/lessons/agents `.versions` homes) stay embedded. Every opener of a default home passes the same non-null legacy home (`SharedStore.open`).
    - **The daemon.** The scheduled daemon's memory stays embedded in `memory-scheduled` (the ADR-026 precedent). Its `run_tournament` reads the user's versioning through the shared host for the duration of a run, and `--memory-home ~/.sophi/memory` opts the daemon into the shared palace. This keeps invariant 4 true after M6.
    - **Reset (Q5, Q13).** `IndexReset` detaches only `context` and sees only engines in its own process, which count as "in use". Other processes' clients are invisible to it; a CLI client's in-flight context call can fail once while the cache is rebuilt, and recovers on its next call. With M6, though, reset returns `InUse` whenever any other process hosts the root (for example the companion with memory only), so the copy says "quit Sophi", not "index in use". Inside the companion a refused reset closes and restarts the engine (`generation++`, one runtime rebuild); this only arises from a second in-process engine, in practice tests.
    - **Self-mod gate.** A baseline jar older than M6 still opens `~/.sophi/versioning/arcadedb` embedded and recreates it. The next M6 start renames that stray directory aside (the marker exists, Q3), so the baseline's scorecards end up there, not in the shared store.
    - **Known limits.** (a) After a downgrade and re-embed, `MemoryVec` still wins over the older inline vector. (b) Vector-index dimensions are fixed at the first `putVec`. (c) The first-use `SophiSchema` marker race is last-write-wins. (d) Mixed CLI/companion versions refuse each other (a differing schema in either direction), so run the same version of both. (e) Memory and versioning are never auto-deleted or rebuilt.

## Reasons

1. **Measured, not assumed.** The database choice was settled by a spike on the real 26.5.1 jar and
   a bake-off on our own repos: retrieval quality was identical across engines, so the deciding
   factors were "one store" and concurrency — and ArcadeDB host + remote passed a 20-second
   two-process test with zero errors and ~39 ms write-to-read freshness.
2. **SQLite was the strongest alternative** (WAL multi-process with no election, real BM25, 4×
   faster at 44k chunks) and was rejected only because it would split storage from Jane's Palace.
3. **Engine-level RPC was rejected** in favour of sharing the database: no new protocol to version,
   and memory/versioning moved onto the same server in M6, removing "memory disabled in the
   second process".
4. **Doubt resolves to rebuild.** The context database is a cache of files on disk; corruption or a
   schema change means recreate and reindex. (Memory and versioning, which joined in M6, are never auto-deleted.)

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
- Schema v2 (M5): a v1 client refuses a v2 host and the first start after upgrading rebuilds every project's index.
- With vectors on, the per-turn hint stays lexical (the score gate is calibrated on BM25).
