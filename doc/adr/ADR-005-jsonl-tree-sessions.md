# ADR-005: Append-only JSONL with parentId tree for sessions

**Date:** 2026-06-27
**Status:** Accepted

## Context

Sophi needs a session format that:
- Survives crashes (durable append-only writes)
- Supports branching (explore alternative completions without losing history)
- Is human-readable and debuggable with standard tools
- Is compatible with the Pi project's session format

Three options considered:
1. Single JSON document per session (rewrite on every save)
2. Append-only log of events (no explicit parent links)
3. Append-only JSONL where each entry has a `parentId` link

## Decision

Use append-only JSONL (one JSON object per line) where each `SessionEntry` carries an
explicit `parentId: String?` that points to its parent entry. The tree is implicit in
the data; branches are created by appending entries with a `parentId` pointing to any
existing entry, not necessarily the last one.

## Reasons

1. **Durable writes.** Appending a line is atomic on all major filesystems. A crash
   mid-write leaves at most one incomplete line, which is discarded on load. (M1's `save()` performs a full file rewrite rather than a streaming append; atomic-append crash durability is the design target realized in the Phase 7 streaming-save enhancement — see Consequences.)

2. **Branching without rewriting.** New branch entries are appended; old branch entries
   remain in the file. No destructive operations on past history.

3. **Human-readable.** Each line is standalone JSON — `grep`, `jq`, and any text editor
   work without a custom viewer.

4. **Pi compatibility.** Pi (earendil-works/pi) uses the same JSONL-tree pattern for
   sessions. Sophi sessions can be read by Pi tooling.

5. **Simple in-memory model.** `AgentSession` loads all entries into a `List`, builds a
   `Map<id, entry>` for O(1) parent lookup, and traverses the chain from tip to root
   for `branch()`. No graph library required.

## Consequences

- Loading reads all lines; file size grows monotonically (compaction is a separate concern, tracked as TODO in Phase 7)
- `save()` rewrites the full file — entries are never truly streamed to disk incrementally in M1 (streaming save is a Phase 7 enhancement)
- Branch entries not on the current tip's lineage remain in the file but are invisible to `branch()` unless `checkout()` is called to switch tips
