# Operating Tollgate

Commands here use the `tollgate` alias from the [quickstart](../README.md#quickstart).

| Port | Serves |
|---|---|
| 8080 | The gateway API, for applications: `/v1/chat/completions`, `/v1/models`, `/anthropic/v1/messages` and `/anthropic/v1/models` |
| 8081 | The admin API, its [OpenAPI contract](api/admin-openapi.json) and the [console](console.md), for operators. Loopback only unless `TOLLGATE_ADMIN_ADDRESS` says otherwise, and every API call needs the admin token |
| 9090 | Health probes and Prometheus metrics at `/actuator/prometheus`. Readiness reports down if the audit trail cannot be written |

- **Who can connect:**
  - The gateway and admin ports listen only on `127.0.0.1` unless
    `TOLLGATE_ADDRESS` and `TOLLGATE_ADMIN_ADDRESS` say otherwise.
  - The image listens on all of its own interfaces, and `docker run -p`
    decides what the host exposes.
  - Tollgate speaks plain HTTP. For clients on other machines, put a
    TLS-terminating proxy in front of it.
  - Port 9090 listens on all interfaces, and its metrics name your tenants
    and models and show their spend. Keep it on a private network, or bind
    it with `MANAGEMENT_SERVER_ADDRESS=127.0.0.1`.

- **Metrics** ([ADR 0017](adr/0017-metrics-traces-and-usage.md)), for
  each tenant, model and provider:
  - `tollgate_requests_total` by result or refusal code
  - `gen_ai_client_token_usage` by token type, and `tollgate_cost_total`
  - `gen_ai_client_operation_duration_seconds` for each provider call, and
    `tollgate_stream_first_event_seconds` for streams
  - `tollgate_budget_spent` and `tollgate_budget_available`, and
    `tollgate_providers_in_flight`
  - `tollgate_limits_refused_total`, `tollgate_providers_busy_total` and
    `tollgate_fallbacks_total`

  Prompts, answers, key IDs and users never appear in metrics. To see them,
  import [docs/grafana/tollgate.json](grafana/tollgate.json) into
  Grafana and pick your Prometheus data source: calls, refusals, spend,
  budgets, tokens, provider latency, fallbacks and calls in flight, filtered
  by tenant, model and provider.

- **Traces:** set
  `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://collector:4318/v1/traces`
  to send OpenTelemetry traces, sampled at 10% unless
  `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` says otherwise. Each request's
  span joins the client's trace when it sends `traceparent`, and has child
  spans for the guards, the redaction scan, the budget, each audit write and
  each provider attempt, with the GenAI token counts. Spans never carry
  prompts or answers, and providers never receive `traceparent`.

- **The admin token:** set `TOLLGATE_ADMIN_TOKEN` to a random string of at
  least 32 characters, for example the output of `openssl rand -hex 32`.
  - **Without it**, the admin API answers `503`.
  - **Requests without the right token** get `401`. A web page cannot send it
    on its own, so a browser cannot be used to call the admin API behind your
    back. The console sends it from code you opened it with, and keeps it in
    the tab only.
- **Reload the config:**
  `curl -X POST -H "Authorization: Bearer $TOLLGATE_ADMIN_TOKEN" http://127.0.0.1:8081/admin/v1/config/reload`.
  A broken file returns `422` and leaves the running config in place. A
  change to the `audit` section returns `409`, because it needs a restart.
- **Usage and budgets:**
  `curl -H "Authorization: Bearer $TOLLGATE_ADMIN_TOKEN" "http://127.0.0.1:8081/admin/v1/usage?from=2026-11-01&by=tenant"`
  sums the audit trail: calls, refusals by code, tokens and cost, by
  `tenant`, `model`, `key`, `user` or `day`. The numbers are the ones
  `audit verify` vouches for. `tollgate usage tollgate.yaml --by model`
  prints the same table, and `/admin/v1/budgets` shows each budget's spent,
  reserved and available amounts.
  `tollgate audit user tollgate.yaml <id>` prints the hash a user
  ID is recorded under, to find that person's row in a report `by=user`. It
  needs the audit keys in its environment.
- **Audit modes:**
  - In `sync` mode, which `tollgate init` writes, each record is fsynced before the request
    moves on, and concurrent requests share fsyncs.
  - `async` mode doesn't wait. It can lose the last records in a crash.
  - If the trail cannot be written, requests are refused with `503`, never
    sent unrecorded.
- **Secrets** are only ever `${env:NAME}` references in the config file.
