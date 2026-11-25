# Inside a Spring Boot application

Add the starter to a Spring Boot 4.1 application on Spring MVC
([ADR 0021](adr/0021-spring-boot-starter.md)):

```xml
<dependency>
  <groupId>io.github.jamal-akhras</groupId>
  <artifactId>tollgate-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

It needs Java 21 or later; Spring Boot 4.1 itself runs on 17, but the starter
does not.

Create the config, the audit keys and a tenant key with the server jar's
`init`, or with `audit keys create` and `keys create --tenant <name>` for an
existing config.
Set `tollgate.config` to the `tollgate.yaml` and give the application the
audit secrets as environment variables, as for the server. It then serves
`/v1/chat/completions`, `/v1/models`, `/anthropic/v1/messages` and `/anthropic/v1/models` with the
same guards, budgets, redaction, audit, metrics and traces.

- **It listens where your application does.** The server binds to
  `127.0.0.1`; your application may listen on every interface, and then
  tenant keys are the only gate.
- **The routes are fixed:** `/v1/*` and `/anthropic/*` belong to the gateway.
- **There is no admin API.** Reload, the usage report and budgets are the
  server's.
- **Settings the server makes for itself,** which you may want too: virtual
  threads, the histogram buckets of `gen_ai.client.operation.duration` and
  `tollgate.stream.first_event`, the Prometheus endpoint, the `audit`
  readiness group, and turning off OTLP metric export. They are in
  [the server's `application.yaml`](../tollgate-server/src/main/resources/application.yaml).
- **Metrics go through Micrometer's global registry.** An application that
  sets `management.metrics.use-global-registry=false` gets none of the
  gateway's metrics.
- **Not supported:** WebFlux applications.
