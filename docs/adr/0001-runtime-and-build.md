# ADR 0001: Runtime, framework and build

Date: 2026-10-29 · Status: Accepted

## Context

Tollgate is meant to be adopted by organizations with conservative platform
standards, including banks, and embedded in their Java services. It also has
to proxy long-lived streaming responses efficiently.

## Decision

- **Java 21 for libraries, JDK 25 for the server.** `tollgate-core` and future
  library modules compile with `--release 21`, the LTS most such organizations
  run, and CI runs core's tests on a Java 21 JVM. The server compiles for and
  runs on JDK 25, the current LTS.
- **Spring Boot with Spring MVC on virtual threads.** Blocking code on virtual
  threads handles high concurrency with straightforward stack traces and
  debugging. JDK 24 removed the main drawback, virtual threads pinning their
  carrier inside `synchronized` (JEP 491), which is another reason the server
  runs on 25.
- **No reactive variant.** Building a second, reactive server only to compare
  would double the surface for little gain. Core is independent of the web
  layer, so this can be revisited with load-test evidence.
- **Maven, with the Maven Wrapper.** It is the build tool these teams already
  run and audit. The wrapper pins Maven's version and verifies the
  distribution's SHA-256 before use.

## Consequences

- Contributors need JDK 25 to build; the enforcer rejects older JDKs.
- Library consumers on Java 21 are supported and tested.
- The server image is based on JDK 25.
