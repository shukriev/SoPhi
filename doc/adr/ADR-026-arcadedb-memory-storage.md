# ADR-026: ArcadeDB as Jane's Palace storage; embedded-only, multi-process split dropped

**Date:** 2026-08-18
**Status:** Implemented

## Context

Jane's Palace (`sophi-memory/jane`) hand-rolled three storage concerns on flat append-only
JSONL: documents (`Memory`, `ProfileAttribute`), a graph (`CausalEdge` links between
memories), and vectors (embeddings, searched via `EmbeddingIndex`'s brute-force cosine scan
— "deliberately no ANN library," per its own comment, matching the reasoning already
established for the unrelated `sophi-learning` Lesson store in ADR-010). `PalaceStore`
re-read and re-parsed the entire log on every access; `CausalEdge` was a flat filtered list
with no real traversal; forget/purge worked by reading everything, filtering in memory, and
atomically rewriting the file.

ArcadeDB is a multi-model embedded database supporting document, graph, and vector (HNSW)
storage in one engine — a close structural match for what Jane's Palace was already
simulating by hand. Two design questions shaped the work: how much of ArcadeDB should leak
into Jane's-Palace-specific code, and whether the CLI and `sophi-web` — both separate JVM
processes that can point at the same `~/.sophi/memory` home — needed to share one database
concurrently, since JSONL's lack of locking had made that free before.

Hands-on verification against the real ArcadeDB 26.5.1 jar (not just its docs) surfaced two
load-bearing findings that shaped the final design:

1. The documented SQL function `vector.neighbors(...)` throws `CommandExecutionException:
   Unknown method name: neighbors` at runtime in this version. Nearest-neighbor search only
   works through the direct Java API — `LSMVectorIndex.findNeighborsFromVector(vector, k)` —
   which is an **embedded-only** object; it isn't reachable through `RemoteDatabase`, whose
   surface is limited to the generic `BasicDatabase` query/command/CRUD interface.
2. A plain `UPDATE` on a pre-existing record doesn't trigger the vector index's incremental
   hook — `putVector` has to call the index's own `put(keys, rids)` directly after the
   `UPDATE`, once the record's RID is known.

Finding 1 is the one with architectural consequences: it means a remote ArcadeDB client
cannot do vector search at all in this version, which directly broke the concurrent-access
design (an embedded host + remote clients sharing one database) partway through
implementation, after the storage layer itself was already built and tested.

## Decision

**Storage layer:** a generic `ArcadeStore` interface (`dev.sophi.memory.store.arcade`) —
vertex/edge/document/vector primitives, no Jane's-Palace types — wraps ArcadeDB.
`PalaceStore` is rewritten on top of it, keeping its existing public method signatures so
`MemoryWriter`/`Consolidator`/`ForgetEngine`/`PalaceWalker`/`UserProfile` need no changes
beyond the ones `EmbeddingIndex`'s removal forces directly. `Memory` becomes a vertex type
(embedding as a vector property + HNSW index); `CausalEdge` becomes a real edge type between
`Memory` vertices — a genuine upgrade over the old flat list, not just a storage swap.
`ProfileAttribute`/`RecallRecord` become document types. `rewriteAll` is deleted; forget/purge
use targeted `deleteMemory`/`deleteEdge`/`deleteAttribute` instead. `audit.jsonl`,
`last-recall.txt`, and `consolidation.marker` deliberately stay plain files — no query need,
and an audit trail independent of the database survives a corrupted one.

**Concurrency: embedded-only, one process at a time.** The planned file-lock launcher
(whichever process opens the store first hosts an embedded `ArcadeDBServer`; any other
process connects as a `RemoteDatabase` client) was implemented through Task 7 of the
migration plan, then reverted on discovering finding 1 above: a remote client in this version
cannot do the one thing the migration is for. `PalaceStore` stays on `EmbeddedArcadeStore`
unconditionally; `close()` is threaded through `ArcadeStore → PalaceStore → JanesPalace →
MemoryPlugin` (ArcadeDB locks its database to one open instance, unlike JSONL, which needed
no such release) so a process that's done with memory frees the lock for the next one.

## Reasons

1. **Verify the real jar, not the docs, before designing around it.** `vector.neighbors()`
   is documented and shown in ArcadeDB's own examples, but doesn't work at runtime in 26.5.1.
   A design built on the documented SQL surface would have shipped broken; the smoke test
   (Task 1 of the migration plan) exists specifically to catch this class of gap before the
   real implementation depends on it — the same discipline ADR-007's MCP work already
   established for third-party SDKs.

2. **A remote client that can't vector-search isn't worth the complexity it costs.** The
   multi-process design was reasonable given what the docs promised; once the real
   constraint was known, keeping it would have meant either a degraded remote path (client-
   side brute-force cosine, reintroducing exactly the O(n) scan this migration replaces, just
   for one process) or a custom RPC layer duplicating what `sophi-hub` already sort of does.
   Neither is justified by a workflow (concurrent CLI + `sophi-web` against the same memory
   home) that isn't a real usage pattern today. Embedded-only, single-process is simpler and
   loses nothing currently used; a shared-server design is revisited only if concurrent usage
   becomes real and either ArcadeDB's remote vector API matures or a deliberate tradeoff
   (brute-force remote fallback, custom RPC) is chosen with eyes open.

3. **Generic storage layer, not a Jane's-Palace-specific one.** `ArcadeStore`'s primitives
   (vertex/edge/document/vector CRUD, keyed by caller-supplied type names) carry no
   `Memory`/`CausalEdge` types, so a second memory technique — should one ever exist — builds
   on the same layer instead of re-solving "how do I talk to ArcadeDB."

   **Addendum (2026-08-18):** this storage layer was subsequently extracted into its own
   module, `sophi-store` (package `dev.sophi.store.arcade`), so consumers other than
   `sophi-memory` can depend on it directly — see
   `docs/superpowers/specs/2026-08-18-sophi-store-extraction-design.md`. The decision recorded
   above (generic layer, not Jane's-Palace-specific) is unchanged; only its address moved.

4. **Not everything needs to move.** `audit.jsonl`/`last-recall.txt`/`consolidation.marker`
   are write-once-read-rarely or single-value state with no query need; keeping them as plain
   files means the audit trail survives even a corrupted or unreachable database — a real
   property specifically worth keeping for an audit log, and a case where migrating
   everything into the new store would have been movement without benefit.

5. **`CausalEdge` as a real graph edge, not just a relocated flat list.** The old
   `List<CausalEdge>` filtered in Kotlin on every `edges()` call had no actual traversal;
   ArcadeDB models it as what it always conceptually was — a graph. This is a genuine
   capability upgrade the migration enables, not merely a storage-format change.
