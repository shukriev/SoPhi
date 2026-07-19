# SoPhi Project Governance

This document describes how decisions are made, how the project is maintained, and what "stable" means for SoPhi.

## Maintainers

### Active Maintainers

- **Shukri Shukriev** ([@shukriev](https://github.com/shukriev)) — Project lead, core agent loop, architecture decisions

Maintainers shepherd the project, review contributions, make release decisions, and ensure the vision stays coherent.

## Decision-Making

### Philosophy

SoPhi uses a **benevolent dictator** model:
- Maintainers welcome discussion and feedback on all decisions
- Contributions are reviewed for correctness, architectural fit, and stability
- Maintainers have final authority on merging contributions and setting direction
- This is not consensus-based, but opinions are heard and respected

### Who Decides What

| Decision Type | Authority | Process |
|---|---|---|
| Bug fixes | Maintainer + PR review | Discuss in issue if ambiguous; review expected |
| Features (existing scope) | Maintainer + PR review | Open issue to discuss; review expected |
| API changes | Maintainer + PR review | Must discuss in issue first; labeled `api-change` |
| **Breaking changes** | Maintainer only | See "Breaking Changes" section below |
| Architecture/design | Maintainer only | See "Architecture Decisions" section below |
| Release timing | Maintainer only | See "Release Cadence" section below |

## Breaking Changes

A breaking change is any modification to the public API (function signatures, return types, behavior, config options) that requires users to update their code to stay compatible.

### Rules for Breaking Changes

1. **Discussion first:** Open a GitHub issue labeled `breaking-change` and discuss the rationale before implementing
2. **Major version only:** Breaking changes can only ship in major version bumps (e.g., 1.0.0 → 2.0.0, never in 1.x.y → 1.(x+1).0)
3. **Deprecation path:** Provide at least two minor releases (≥2 minor releases, x.y.0) where the old way still works but emits a deprecation warning
4. **Changelog entry:** Document clearly in CHANGELOG.md under `### Removed` with migration guidance

### Examples

✅ **Good breaking change proposal:**
> "Remove `Session.output` (unused since v0.5) with deprecation warning in v1.1, removal in v1.3 (≥2 minor releases later)"

❌ **Not allowed:**
> "Change function signature in v1.5.0" (must wait for v2.0.0)

## Architecture Decisions

Major architecture decisions (new subsystems, core refactors, significant plugin interfaces) should be documented as Architecture Decision Records (ADRs).

**When to write an ADR:**
- Adding a new major module or subsystem
- Significant changes to core agent loop
- New cross-cutting concerns (logging, metrics, etc.)

**ADR location:** (To be added) `doc/adrs/NNNN-decision-title.md`

**Format:** Title, context, decision, consequences, alternatives considered.

**Process:**
1. Open an issue to discuss the decision
2. Draft an ADR and share it in the issue
3. Gather feedback from community
4. Maintainer approves and merges
5. Implementation follows, referencing the ADR

## Stability Definition

SoPhi is considered "stable" when:

1. ✅ All tests pass (`mvn test`)
2. ✅ Integration tests cover the feature/fix
3. ✅ No regressions in existing surfaces (CLI, web, SDK)
4. ✅ Code review is complete
5. ✅ CHANGELOG.md is updated

## Release Cadence

### Stable Releases
- **Frequency:** Approximately quarterly (every ~3 months)
- **Versioning:** x.y.0 (e.g., 1.1.0, 1.2.0, 2.0.0)
- **Timing:** Merged into `main`, tagged as release, binaries published to Maven Central

### Patch Releases
- **Frequency:** As needed for critical bugs, security issues
- **Versioning:** x.y.z (e.g., 1.1.1, 1.2.3)
- **Criteria:** Only bug fixes, no features or refactoring

### Pre-Release Versions
- **Use case:** Breaking changes, major features in testing phase
- **Versioning:** x.y.0-rc1, x.y.0-rc2, etc. (release candidates)
- **Availability:** Published to Maven Central; users opt-in to testing

### Version Numbers
SoPhi uses [Semantic Versioning](https://semver.org/):
- **MAJOR** (x.0.0): Breaking changes, incompatible API changes
- **MINOR** (x.y.0): New features, backwards-compatible additions
- **PATCH** (x.y.z): Bug fixes, no API changes

## Contribution Scope

SoPhi welcomes contributions in all areas, with different review intensity depending on scope:

### 🟢 Open — Lightweight Review

**Tools & plugins:** New tools, plugins following existing patterns
- **Examples:** New tool implementations, PermissionGatePlugin variants
- **Expectation:** Tests required, follows established patterns

**Integrations:** MCP integration, new LLM providers, deployment modes
- **Examples:** New OpenAI-compatible provider, MCP server wrapper
- **Expectation:** Integration tests required, clear documentation

**Learning/Memory research:** New memory techniques, learning strategies
- **Examples:** Alternative memory-palace algorithm, new consolidation strategy
- **Expectation:** Experimental features behind flags, clear docs on status

### 🟡 Restricted — Design Review Required

**Core agent loop:** Changes to agent.kt, session dispatch, turn execution
- **Examples:** Modifying how tools are called, session persistence format
- **Expectation:** Open GitHub issue first, discuss architecture before PR

**API surfaces:** Changes to public classes, method signatures
- **Examples:** Adding/removing parameters, changing return types
- **Expectation:** Discuss in GitHub issue first, may trigger ADR

### Areas Reserved for Maintainers

**Project vision & roadmap** — Maintainers set strategic direction  
**Release decisions** — When to release, which branches, versioning  
**License changes** — Apache 2.0 is the license; changes require consensus

## Community

- **Questions?** Open a [GitHub Discussion](https://github.com/shukriev/SoPhi/discussions)
- **Bug report?** Open an [Issue](https://github.com/shukriev/SoPhi/issues) with reproduction steps
- **Have an idea?** Open an issue to discuss before investing effort

Thank you for helping shape SoPhi!
