# Contributing to SoPhi

Thank you for your interest in contributing! SoPhi welcomes contributions from researchers, developers, and ecosystem builders. This guide explains how to get involved.

## Quick Start

1. **Fork the repository** on GitHub
2. **Clone your fork** locally: `git clone https://github.com/YOUR_USERNAME/SoPhi.git`
3. **Create a branch** for your work: `git checkout -b feature/my-feature`
4. **Make your changes**, write tests, and update CHANGELOG.md
5. **Push to your fork** and open a pull request

## Local Setup

### Prerequisites
- JDK 21 or later
- Maven 3.9+
- For local LLM support: Ollama or vLLM

### Build
```bash
mvn -q -DskipTests package
```

### Run Tests
```bash
mvn test
```

### CLI Demo
```bash
# With Claude (Anthropic API key required)
export ANTHROPIC_API_KEY=sk-ant-...
java -jar sophi-cli/target/sophi-cli-1.0.0-SNAPSHOT.jar

# With local models (Ollama example)
java -jar sophi-cli/target/sophi-cli-1.0.0-SNAPSHOT.jar \
  --provider openai-compat \
  --base-url http://localhost:11434/v1 \
  --model qwen2.5:7b \
  --llm-timeout-seconds 300
```

## What Maintainers Expect

### Before You Submit a PR

1. **Tests must pass:** Run `mvn test` and ensure all pass
2. **Changelog entry:** Add your change to `CHANGELOG.md` under the `[Unreleased]` section
3. **Clear commit messages:** Use imperative mood ("add feature" not "added feature"), reference related issues
4. **No console noise:** Remove debug logging, temporary print statements, or IDE cruft

### Changelog Format

Add entries to the `[Unreleased]` section in one of these categories:
- `### Added` — new features
- `### Changed` — changes to existing features
- `### Fixed` — bug fixes
- `### Deprecated` — features marked for future removal
- `### Removed` — features that were previously deprecated
- `### Security` — security fixes

Example:
```
### Added
- Support for embedding models via OpenAI-compatible endpoints (#42)

### Fixed
- Memory probe now shows actual error instead of generic "unreachable" message (#41)
```

### Code Style & Patterns

Follow existing patterns in the codebase:
- **Kotlin conventions:** Use Kotlin idioms (data classes, sealed classes, extension functions)
- **Test patterns:** Follow the existing tests in `sophi-*/src/test/kotlin` (MockK for mocking, JUnit 5 structure)
- **No trailing whitespace or debug output**

### Breaking Changes

A breaking change affects the public API (function signatures, behavior, config options) in a way that requires users to update their code.

**If you're making a breaking change:**
1. Open a GitHub issue labeled `breaking-change` and discuss first
2. Breaking changes can only ship in major version bumps (x.0.0)
3. Provide a deprecation path: warn for ≥2 minor releases before removing

Example: If removing a method, deprecate it with `@Deprecated("Use newMethod instead")` for ≥2 releases before deleting.

## Contribution Scope

Different parts of SoPhi have different openness:

| Area | Status | Notes |
|------|--------|-------|
| Learning/Memory research | 🟢 Open | Experimental features go behind flags |
| Core agent loop | 🟡 Restricted | Changes need design review (open an issue first) |
| New tools/plugins | 🟢 Open | Must follow existing patterns and include tests |
| Integrations (MCP, etc.) | 🟢 Open | Must include integration tests |

## Review Process

1. **CI must pass:** All GitHub Actions checks (tests, formatting) must pass
2. **Code review:** A maintainer will review for correctness, style, and architectural fit
3. **Feedback:** Maintainers may request changes; push new commits to your PR branch
4. **Merge:** Once approved, your PR will be merged to `main`

## Questions?

- **How do I...?** → Check the README or docs in `doc/`
- **Should I...?** → Open an issue to discuss before investing effort
- **Found a bug?** → Open an issue with a minimal reproducible example

Thank you for contributing to SoPhi!
