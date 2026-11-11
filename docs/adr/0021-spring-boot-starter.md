# ADR 0021: A Spring Boot starter that embeds the gateway

Date: 2026-11-11 · Status: Accepted

## Context

Tollgate 0.1.0 goes to Maven Central as `tollgate-core` and
`tollgate-providers`. Neither can run a gateway on its own: the request
pipeline, the three gateway endpoints, the audit sink and the budget replay
all live in `tollgate-server`, mostly in the package-private `Gateway`. A Java
team that finds Tollgate on Central today gets parts, not a product.

The design has always planned a Spring Boot starter (M2). The server is already
a Spring Boot 4 application, so the starter is mostly a move.

## Decision

### What the starter is

- **A new module, `tollgate-spring-boot-starter`,** compiled for Java 21 like
  the other libraries and published to Maven Central with them.
- **A Spring Boot 4.1 application that adds it** and sets `tollgate.config` to
  a `tollgate.yaml` serves `/v1/chat/completions`, `/v1/models` and
  `/anthropic/v1/messages` on its own port, with the same guards, budgets,
  redaction, audit, metrics and traces as the server.
- **It is still HTTP.** The application's own SDK client calls the gateway on
  loopback. An in-process Java API is a later decision.
- **Missing config fails startup** with the server's existing message. An
  application can't add the starter and leave it idle.

### What moves

- **From `server/gateway`:** `Gateway`, `GatewayConfiguration`,
  `GatewayProperties`, `GatewayException`, `GatewayMetrics`, `ConfigFile`,
  `LoadedConfig`, `BudgetReplay`, the three gateway controllers and two error
  handlers, `Endpoints`, `Relay`, `ServletStream`, `StreamErrors`,
  `StreamTarget`, `TraceIdFilter` and `ReloadRejected`.
- **From `server/audit`:** `JsonLinesAuditSink`, `AuditJson`,
  `AuditDirectory` and `UsageReport`.
- **`StartupException` and `StartupFailureAnalyzer`** move together, so an
  embedding application gets the same one-line startup message as the server
  rather than a stack trace. The analyzer is registered in the starter's
  `META-INF/spring.factories`.
- **Packages:** `io.github.jamalakhras.tollgate.gateway` and
  `io.github.jamalakhras.tollgate.gateway.audit`.
- **The move is a pure move.** No behaviour changes in the same commit, and
  the admin OpenAPI contract stays byte-for-byte the same.

### What stays in the server

The application class, the CLI, the admin package (its own port, the token
filter, port separation and API docs), the admin endpoints `ConfigController`
and `UsageController`, `application.yaml` and the Jib image. The admin API
needs its own port and token, so an embedded gateway has no reload, usage or
budget endpoints in 0.1.0.

### The public surface

Only what the server needs becomes public:

- `Gateway`, with `reload()`, `config()`, `budgetStore()` and
  `auditDirectory()`, and its `Reload` result.
- `ReloadRejected` and `StartupException`.
- `ConfigFile`, `LoadedConfig`, `AuditDirectory` and `UsageReport`, which the
  CLI already uses.

Everything else stays package-private. Until 1.0 these types may change
between minor versions, as the changelog already says of the extension
interfaces.

### What it brings into the host application

- **Required:** Spring MVC on a servlet container, since the controllers, the
  stream relay and the trace-ID filter use the Servlet API; Jackson's YAML
  module, to read `tollgate.yaml`; Micrometer and the Micrometer Tracing API;
  and Spring Boot's health module, for the audit health indicator.
- **Optional:** a Prometheus registry and the actuator endpoint to expose the
  metrics, and Spring Boot's OpenTelemetry starter for traces. Without a
  tracer the gateway records no spans, as the server does without an OTLP
  endpoint.
- **Not supported:** WebFlux applications.

### Routes

The gateway claims `/v1/*` and `/anthropic/*` in the host application, and the
trace-ID filter runs on every request under them, including any the host
serves itself. There is no setting for a prefix in 0.1.0.

### Wiring

- **Auto-configuration** is registered in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
  imports the controllers and filter explicitly rather than scanning, and
  enables `GatewayProperties`.
- **The starter sets none of the host application's properties.** The server
  keeps setting virtual threads, the histogram buckets of the two timers, the
  Prometheus endpoint and the `audit` readiness group in its own
  `application.yaml`. The README lists them for embedding applications.

## Alternatives considered

- **Publishing the server to Central.** A fat jar with an admin port is an
  application, not a dependency; nobody adds it to their own build.
- **An in-process Java API instead of HTTP.** It would skip the loopback hop,
  but it is a second way into every guard and budget, designed in a day.
- **A separate autoconfigure module next to an empty starter,** as Spring
  itself does. It matters for a library with many optional parts; this one
  has a single part.
- **Leaving the code in the server and depending on it.** The server compiles
  for Java 25 and carries the CLI, the admin port and Jib.

## Consequences

- **A Java team can run Tollgate inside an existing Spring Boot application**
  by adding one dependency and a config file.
- **The server becomes a thin application** around the starter, and both are
  tested on every build.
- **Some gateway types become public API** in a published jar.
- **Embedded gateways have no admin API** in 0.1.0.
- **An embedded gateway listens wherever the host application does.** The
  server binds to `127.0.0.1` because its own `application.yaml` says so; a
  host application usually listens on every interface, and then tenant keys
  are the only gate. The README says so where it shows embedding.
- **Spring Boot 4.1 only.** Other versions may work but aren't tested.
