# ADR-004: LLM contract placement in sophi-ai

**Date:** 2026-06-27
**Status:** Accepted

## Context

`LLMProvider`, `CompletionRequest`, and `LLMResponse` must be visible to `sophi-core` so the
agent loop can call the provider without importing Spring AI. Two placement options:

1. A new `sophi-contracts` module (no deps) that both `sophi-ai` and `sophi-core` depend on.
2. Put the contract in `sophi-ai` — `sophi-core` already depends on `sophi-ai`.

## Decision

Place the contract in `dev.sophi.ai.api` inside `sophi-ai`. No `sophi-contracts` module.

## Reasons

1. **`sophi-core` already depends on `sophi-ai`** (see `sophi-core/pom.xml`). Importing the
   interface from `dev.sophi.ai.api` introduces no new dependency arc.

2. **YAGNI.** A `sophi-contracts` module exists to break a circular dependency or to allow
   a third party to depend on the contract without the implementation. Neither condition
   holds today. Extract when — and if — it becomes necessary.

3. **The Spring AI isolation boundary is the package, not the module.** ADR-002 says Spring AI
   types must not escape `sophi-ai`. A sub-package (`dev.sophi.ai.api`) achieves this at zero
   structural cost.

## Consequences

- `sophi-core` imports `dev.sophi.ai.api.*` — this is the only `sophi-ai` import allowed in `sophi-core`
- If a future module needs `LLMProvider` without Spring AI on the classpath, extract `sophi-ai-api`
  as a separate module at that point
- Re-evaluation trigger: any module other than `sophi-core` needing the LLM contract
