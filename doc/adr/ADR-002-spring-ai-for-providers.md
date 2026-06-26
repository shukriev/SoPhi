# ADR-002: Spring AI for provider abstraction

**Date:** 2026-06-27
**Status:** Accepted

## Context

Sophi needs to send requests to Claude, GPT, Gemini, and Ollama. Options:
1. Write HTTP clients directly (Anthropic Java SDK, OpenAI Java SDK, etc.)
2. Use Spring AI 2.0 starters as the provider abstraction layer
3. Use LangChain4j, which also wraps multiple providers

## Decision

Use Spring AI 2.0.0-RC1 starters in `sophi-ai`. Sophi does not write its own provider HTTP
clients.

## Reasons

1. **Spring AI 2.0 provides auto-configured `ChatModel` beans** for every major provider via Boot
   starters. Writing equivalent adapters is weeks of work with no product value.

2. **Spring AI BOM manages provider versions.** One BOM import tracks Anthropic, OpenAI, and
   Google SDK versions automatically.

3. **Spring AI stays in `sophi-ai` only.** The agent loop (`sophi-core`) talks to `LLMProvider`,
   not Spring AI types. If Spring AI is replaced, only `sophi-ai` changes.

4. **LangChain4j rejected** because it is opinionated about orchestration (chains, tools) in ways
   that conflict with Sophi's custom loop.

## Consequences

- Spring AI 2.0.0-RC1 quality risk is contained entirely to `sophi-ai` — the agent loop has
  zero Spring AI imports
- Spring Milestones repository must be declared in the parent POM for RC artifact resolution
- Upgrade to Spring AI GA when released; only `sophi-ai/pom.xml` changes
