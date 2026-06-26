# ADR-001: Custom agent loop vs Koog

**Date:** 2026-06-27
**Status:** Accepted

## Context

JetBrains released Koog 1.0, a Kotlin-native agent framework with a graph DSL, GOAP planner,
A2A protocol support, KMP targets, and LiteRT for Android. It is the most prominent JVM-native
option for building agent runtimes as of mid-2026.

Question: build `sophi-core` on top of Koog, or write it from scratch?

## Decision

Write `sophi-core` from scratch. Evaluate Koog only if Sophi later needs graph-based
multi-agent orchestration.

## Reasons

1. **The agent loop is ~600 lines.** Sophi's loop (prompt → LLM → tool calls → results → LLM)
   is a well-specified, short program. Writing it is days, not months.

2. **The harness IS the product.** "Built on Koog" means Koog's design decisions become Sophi's
   public behaviour. The session format, event shape, and tool contract must be Sophi's to own.

3. **Koog carries framework tax.** Graph DSL, GOAP planner, A2A protocol, KMP, LiteRT Android —
   none are needed for Sophi's target (JVM, single-agent, Spring Boot embedding).

4. **JSONL session format.** Pi compatibility requires owning the session format exactly.
   Koog does not expose this.

5. **Skill system and TUI are custom regardless.** Neither is in Koog, so we write them from
   scratch whether or not Koog is used for the loop.

## Consequences

- `sophi-core` is maintained by the Sophi project — no upstream to escalate bugs to
- Estimated initial size: 600–800 lines for working core; ~2,000 with compaction + hooks
- Koog re-evaluation trigger: graph-based multi-agent orchestration or A2A protocol requirement
