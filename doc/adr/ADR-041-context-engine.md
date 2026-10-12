# ADR-041: Context engine on a shared, lock-elected ArcadeDB store

**Date:** 2026-10-12
**Status:** Accepted — M0 (shared store) and M1 (lexical engine) implemented; M2 planned

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
   project is refused, registering a parent replaces its children); `isGlobal` marks a project that is
   always searched. Enumeration skips build/vendor directories, symlinks, secret files, binaries and
   files over 1 MB; lockfiles and minified files are indexed as `generated` (metadata only). Scans are
   cancellable and an unready store answers `index not ready — use grep/glob/read` instead of failing a
   turn. Tools: `search_context`, `read_context`, `project_map`; CLI: `sophi context add|list|remove|reset|eval`.
   Measured with `sophi context eval` (evals/context/RESULTS.md): on the 31 handwritten queries hit@10
   0.97, MRR 0.82 (grep MRR 0.12); on 258 git-mined commit queries engine MRR 0.63 vs grep 0.26 (2.4x).
   Chosen weights: k1 0.9, b 0.4, name boost 1.5, at most 3 hits per directory, role priors
   SOURCE/INSTRUCTION 1.0, DOC/CONFIG 0.9, TEST 0.6, GENERATED 0.2. The no-priors BM25 baseline
   scores 0.94 / 0.79 on today's repos, so the priors and the k1/b retune earn the margin. Orientation,
   a gated per-turn hint and a token budget follow in M2.

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
