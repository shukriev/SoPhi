# ADR-003: Maven multi-module build

**Date:** 2026-06-27
**Status:** Accepted

## Context

Build system options for a multi-module Kotlin JVM project targeting Spring Boot 4.0:
1. Maven multi-module with parent POM
2. Gradle Kotlin DSL with version catalog
3. Monorepo with separate Gradle projects per module

## Decision

Maven multi-module with a parent POM importing Spring Boot BOM, Spring AI BOM, and Kotlin
coroutines BOM.

## Reasons

1. **Spring Boot 4.0 is Maven-primary.** The Spring Initializr, official docs, and BOM-based
   dependency management are most natural in Maven.

2. **BOM composition is explicit.** Three `<import>` entries in `<dependencyManagement>` give
   full visibility over version resolution. Gradle's BOM behaviour is less transparent.

3. **Kotlin Maven plugin inherits cleanly.** One plugin configuration in the parent applies to
   all child modules — no per-module plugin setup needed.

4. **Gradle rejected** primarily because Spring AI 2.0 RC1's Gradle plugin support was less
   mature than Maven at decision time.

## Consequences

- Incremental build times are slower than Gradle
- Module build order is determined by Maven reactor from `<dependency>` declarations
- `mvn test` = unit tests only; `mvn verify` = unit + integration tests
- `skipITs=true` is the default in the parent — integration tests opt-in via `mvn verify`
