# ADR 0002: A framework-free core

Date: 2026-10-29 · Status: Accepted

## Context

Tollgate runs in two ways: as a standalone server, and embedded in other
applications. Embedding is only attractive if it does not bring a framework,
a web server or a dependency conflict along with it.

## Decision

- `tollgate-core` holds the request model, policy pipeline, decision trace,
  extension interfaces and domain types. It may depend only on the JDK and
  JSpecify's nullness annotations.
- An ArchUnit test enforces the rule, so a stray import fails the build rather
  than a code review.
- Framework integration lives at the edges: `tollgate-server` and the Spring
  Boot starter adapt core to Spring; provider adapters live in their own
  modules.
- Every package is `@NullMarked`, and NullAway checks nullness at compile time.

## Consequences

- Core cannot use Spring conveniences such as dependency injection or
  configuration binding; it uses plain constructors and records instead.
- Core can be embedded in any JVM application, Spring or not.
- Extension interfaces are plain Java, so custom guards do not depend on a
  framework version.
