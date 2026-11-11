# ADR 0017: Metrics, traces and a usage report

Date: 2026-11-10 · Status: Accepted

## Context

Operators can see what Tollgate did only by reading the audit trail or the
log:

- **The metrics can't be read.** ADRs 0015 and 0016 added counters, but the
  management port exposes only `health` and `info`, so nothing scrapes them.
- **There are no traces.** A slow call can't be split into the time spent in
  guards, the audit and the provider, and a Tollgate call can't be joined to
  the client's own trace.
- **Spend is visible only for tenants with a budget,** and only as a refusal.
  Nothing answers "what did each tenant use this month?"

Every competing gateway ships dashboards. Tollgate's version should keep its
own rule: prompts and answers never leave as text.

## Decision

### Metrics, scraped from the management port

- **`/actuator/prometheus` on port 9090,** next to the health probes, through
  Micrometer's Prometheus registry. Nothing is pushed anywhere by default.
- **Calls and their cost,** recorded with each outcome:

  | Metric | Type | Tags |
  |---|---|---|
  | `tollgate.requests` | counter | tenant, model, provider, result |
  | `gen_ai.client.token.usage` | histogram | tenant, model, provider, `gen_ai.token.type` (input, output, cache_read, cache_write) |
  | `tollgate.cost` | counter, in the currency's units | tenant, model, currency |
  | `gen_ai.client.operation.duration` | timer | model, provider, result |
  | `tollgate.stream.first_event` | timer | model, provider |

  `result` is the outcome's result (`responded`, `timed_out` and so on) or
  the refusal's code (`policy_denied`, `budget_exceeded`). The two `gen_ai`
  names follow the OpenTelemetry GenAI conventions, so existing GenAI
  dashboards work.
- **State,** as gauges: each budget's spent and available amount, and the
  calls in flight to each provider.
- **The existing counters stay:** `tollgate.limits.refused`,
  `tollgate.providers.busy` and `tollgate.fallbacks`.
- **No key IDs or data in tags.** Tenants, models and providers come from the
  config, so the number of series is bounded by it.

### Traces, off until an endpoint is set

- **OpenTelemetry through Micrometer Tracing,** exported over OTLP when
  `management.opentelemetry.tracing.export.otlp.endpoint` is set. Spring
  Boot's sampling setting applies, at 10% by default. Spring Boot's OTLP
  metrics export is switched off, so metrics stay pull-only.
- **One span per request,** joined to the client's trace when it sends
  `traceparent`, with child spans for the guards, the redaction scan, the
  budget, each audit write and each provider attempt.
- **Provider spans use the GenAI attributes:** `gen_ai.provider.name`,
  `gen_ai.request.model` and the token counts, plus `tollgate.trace_id` so a
  span leads to its audit records. `gen_ai.response.model` was planned too and
  is not set yet.
- **No content, ever.** The GenAI conventions allow prompts and answers as
  span events. Tollgate never records them, because a trace backend is a
  second copy of the data that the audit trail is designed not to keep.
- **Nothing is propagated to providers.** `traceparent` is not forwarded, so
  a provider learns nothing about the client's systems.

### A usage report from the audit trail

- **`GET /admin/v1/usage?from=…&to=…&by=tenant|model|key|day`** on the admin
  port, and `tollgate usage` on the command line, sum the audit trail:
  requests, refusals by code, tokens by type, and cost.
- **The audit trail is the source** because it is complete, signed and
  survives restarts. The numbers are the ones `audit verify` vouches for, not
  a second count that could drift from them.
- **Without an audit trail** the report answers `409` and says why.
- **It reads the files on each request.** That is fine for the
  single-instance tier; the Postgres tier will answer the same request from a
  query.
- **`GET /admin/v1/budgets`** lists each budget's limit, period, spent,
  reserved and available amounts, live from the budget store.

## Alternatives considered

- **Usage from the metrics.** Simpler, but counters reset on restart and are
  not evidence. Metrics are for live dashboards; the report is for invoices
  and audits.
- **A separate usage ledger.** It would duplicate the audit trail and could
  disagree with it.
- **Exporting metrics over OTLP as well.** Possible later through
  configuration alone; Prometheus scraping is the common case and needs no
  outbound connection.
- **Tracing on by default.** It would send data to an endpoint nobody chose.
- **Recording prompts in spans as an option.** Even opt-in, it would undo the
  audit's design.

## Consequences

- **Dashboards and alerts work** with standard Prometheus and GenAI tooling.
- **A slow request can be broken down,** and joined to the client's trace.
- **Spend per tenant, model, key or day** comes from the signed trail.
- **The report gets slower as the trail grows** until the Postgres tier.
- **Metric tags expose tenant and model names, and spend,** to anyone who can
  reach port 9090, which listens on all interfaces. The README will say to
  keep it on a private network or bind it with `management.server.address`.
