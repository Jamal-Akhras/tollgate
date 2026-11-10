# Changelog

All notable changes to this project are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0.0, minor
versions may change the extension interfaces.

## [Unreleased]

### Added

- Maven build with Error Prone, NullAway, Spotless, JaCoCo coverage floors, PIT
  mutation testing, an SBOM, and enforcer rules.
- `Money` and `TokenPrice`: exact nano-unit money and token pricing that
  rounds up, checked against `BigDecimal` by property tests.
- Server skeleton with the admin API on its own port, a published OpenAPI
  contract checked in CI, and health probes on the management port.
- `tollgate.yaml` configuration: providers, models with prices and output
  caps, tenants, hashed keys, data classes and policies. Secrets are
  `${env:...}` references only.
- OpenAI chat completions and Anthropic messages endpoints that forward each
  request in its own format, plus `/v1/models` for each tenant. Errors come
  back in the format of the endpoint that was called.
- `keys create`, which prints a new tenant key once with the config line that
  matches it.
- `init`, which writes a working `tollgate.yaml` and a `tollgate.env` holding
  the audit keys and an admin token. The secrets file is readable only by its
  owner from the moment it exists, nothing is ever overwritten, and only the
  tenant key is printed.
- Request size and data-class guards. Data classes can only be made stricter,
  and each class goes only to the regions it allows.
- Per-tenant budgets: the worst case is reserved before the call, settled from
  reported usage, and over-budget requests are refused with `429`.
- Prices can set a `reasoning` rate for reasoning tokens and `longContext`
  tiers that price the whole call once its prompt reaches `fromTokens`.
- A price catalog generated from [models.dev](https://models.dev) ships inside
  the jar, with `scripts/update-prices.py` to regenerate it.
- `price` is optional for models on known hosts, such as OpenAI, Anthropic and
  DashScope, and for providers that name a catalog with `catalog:`; the
  catalog's price is used. `price: free` marks a model that costs nothing. A
  model with no price and no catalog entry stops the config from loading.
  The config version now includes the catalog version.
- `prices show <provider> <model>` prints a catalog price as config, and
  `prices pin <tollgate.yaml>` prints the catalog prices a config uses, so a
  team can paste them in and own them.
- A weekly workflow regenerates the price catalog and opens a pull request
  when a price changes, so every change is reviewed before it ships.
- Tenants with `showCosts: true` get `X-Tollgate-Cost-Max` and
  `X-Tollgate-Cost` on each response. A stream carries the maximum in its
  headers and ends with `: tollgate-cost <amount>`.
- `X-Tollgate-Dry-Run: true` returns a cost estimate without calling the
  model or reserving any budget. It runs every check a real call would, is
  audited with the result `dry_run` and a cost of zero, and is only for
  tenants with `showCosts`.
- Redaction of patterned personal data
  ([ADR 0012](docs/adr/0012-redact-personal-data.md)). A policy's `redact`
  setting replaces email addresses, card numbers, IBANs, phone numbers,
  UK National Insurance and US Social Security numbers, and IP addresses with
  placeholders such as `[EMAIL_1]` for the data classes it names, and the
  answer gets the real values back, in streams and tool-call arguments too.
  Its `refuse` setting stops a request that carries an API key or private key
  with `403`. Intents with redactions record how many values of each kind
  were replaced and an HMAC of what was sent, as version 2 of the audit
  record format; every other record stays at version 1.
- Detectors of your own under `detectors:`, as a regular expression or a list
  of terms, used in `redact` and `refuse` like the built-in ones
  ([ADR 0013](docs/adr/0013-custom-detectors.md)). A named group `value`
  limits what a pattern replaces, terms match whole words in any case, and
  `tollgate.max-scan-time` (200 ms by default) refuses a request with `500`
  `redaction_timeout` rather than let a slow pattern hold it.
- Limits per tenant on requests a minute, tokens a minute and calls at a time
  ([ADR 0015](docs/adr/0015-tenant-rate-limits.md)). A tenant over a limit
  gets `429` `rate_limited` with `Retry-After` before its request is read.
  These refusals are counted in the `tollgate.limits.refused` metric rather
  than audited, and each instance enforces the limits on its own.
- `concurrent` on a provider caps the calls in flight to it across all tenants
  ([ADR 0016](docs/adr/0016-provider-fallback.md)). A call over the limit gets
  `503` `provider_busy` at once, is not audited, and is counted in the
  `tollgate.providers.busy` metric.
- Models can name fallbacks with `fallback:`, tried in order when the provider
  can't be reached, times out, is busy, or answers `401`, `403`, `408`, `429`
  or `5xx` ([ADR 0016](docs/adr/0016-provider-fallback.md)).
  - **Policy:** each fallback is checked by the tenant's policy and budget
    again, and a refused one is skipped.
  - **Formats:** a fallback must use the same format as the model it backs up.
  - **Streams:** they fall back only before the first byte reaches the client.
  - **Audit:** each attempt is its own intent and outcome, with the trace ID
    followed by `.2`, `.3` and so on.
  - **Metric:** `tollgate.fallbacks` counts each move to the next model.
- Prometheus metrics at `/actuator/prometheus` on the management port
  ([ADR 0017](docs/adr/0017-metrics-traces-and-usage.md)): requests by result,
  tokens by type and cost for each tenant, model and provider, provider
  latency, time to a stream's first event, budget spent and available, and
  calls in flight to each provider. The token and latency metrics use the
  OpenTelemetry GenAI names, and provider latency and time to first event
  have histogram buckets for percentiles.
- A Grafana dashboard in `docs/grafana/tollgate.json` for those metrics.
- OpenTelemetry traces over OTLP, off until an endpoint is set. A request's
  span joins the client's trace and has a child span for each step and each
  provider attempt. Spans never carry prompts or answers, and `traceparent`
  is never sent to providers.
- A usage report summed from the audit trail, on the admin port at
  `GET /admin/v1/usage` and on the command line as `tollgate usage`: calls,
  refusals by code, tokens and cost by tenant, model, key or day.
  `GET /admin/v1/budgets` shows each budget's spent, reserved and available
  amounts.
- A circuit breaker for each provider. After five failures in a row, calls
  skip the provider for 30 seconds and go to a fallback, or get `503`
  `provider_unavailable` with `Retry-After` at once. Then one call tries the
  provider again, and an answer closes the breaker.
- A signed, batch-chained audit trail. Records are hashed from a fixed binary
  encoding into RFC 6962 Merkle trees. Each root is chained to the one before
  and signed with Ed25519, and prompts are kept only as HMACs. Writes are
  synchronous with group commit, or asynchronous. A request whose intent
  cannot be recorded is refused.
- `audit keys create` and `audit verify`.
- `POST /admin/v1/config/reload`, which validates the file and swaps it in
  atomically.
- A container image on a distroless Java 25 base, built with Jib. Pushing a
  `v*` tag publishes it to `ghcr.io/jamal-akhras/tollgate` with a build
  provenance attestation, and drafts a GitHub release with the jar and SBOM.
  A tag that does not match the project version publishes nothing.
- The log says which address each port listens on at startup, and how to
  accept other machines when only localhost can connect.
- Startup problems, such as a missing or invalid config, an unset secret or an
  audit directory already in use, are reported in one short message without
  a stack trace.

### Security

- Every `/v3/api-docs` path, including the YAML document, is served only on the
  admin port.
- A config is rejected if any tenant's policy has no data-class guard, so a
  tenant's data class can never go unenforced.
- Requests are checked against an allowlist before they are forwarded, and the
  refusal is audited. Tollgate refuses:
  - fields it has not reviewed, and `anthropic-beta` headers
  - provider-side tools such as web search, web fetch and code execution
  - fields that send data elsewhere or change pricing, such as `mcp_servers`,
    `store: true`, and `speed: "fast"`, which bills Anthropic's fast mode at
    twice the price
  - images, documents and files that the provider would fetch by URL or ID
- Policies can allow `remote-images`, `remote-documents`, `provider-files` and
  `unreviewed-fields` for named data classes. `unreviewed-fields` lets clients
  such as Claude Code send new fields and betas. Server tools, refused fields
  and refused betas stay blocked.
- Anthropic web search can be allowed with the `web-search` feature.
  - **Searches are capped:** Tollgate caps each call's searches at the
    model's `maxWebSearches`.
  - **Budgets include the fee:** budgets reserve the search fee from
    `price.webSearch`, priced per 1,000 searches, and charge the searches the
    provider reports.
  - **Pricing is checked at startup:** a policy that allows web search makes
    that price a required setting.
- A provider can list `extraFields`, its own request settings such as Ollama's
  `top_k` or `num_ctx`, which Tollgate then forwards. Fields Tollgate refuses
  for where they send data or how they are billed cannot be listed.
- Budget reservations count every answer that `n` asks for, and add 5,000
  tokens for each inline image.
- Near the limit, a call's output cap is lowered to fit what is left, and
  `X-Tollgate-Output-Limited` says so. The cap never goes below
  `maxOutputTokens.floor`, 256 by default. Below the floor the call is
  refused with `429`.
- Provider responses are bounded.
  - **Size:** a body larger than `tollgate.max-response-size` (32 MB by
    default) is cut off and reported as `502 provider_invalid_response`.
  - **Time:** a provider's `timeout` now covers the whole response, so a body
    sent slowly cannot hold a call open.
  - **Audit and billing:** such calls are audited with the result
    `invalid_response` and charged their full reservation.
- Provider responses are rebuilt.
  - **Successful bodies** must be JSON objects, and their `model` is your
    model's name, not the provider's.
  - **Provider errors** keep the provider's message only for `400`, `404`,
    `413` and `422`. A `429` keeps its `Retry-After` but says no more, and
    provider failures become `502`.
  - **Every response** is `application/json` with
    `X-Content-Type-Options: nosniff`.
- `max_tokens` is filled in only for tenants with a budget, so other requests
  keep the provider's own default and thinking models are not cut short.
- A weekly workflow checks the distroless base image. When its digest has
  moved, the workflow opens a pull request with the new one and runs CI on
  it, so the image's Java runtime and libraries keep getting patches.
- Audit trails are private on Linux and macOS: the directory is `0700` and
  its files `0600`, including files written by earlier versions.
- The gateway port listens only on `127.0.0.1` unless `TOLLGATE_ADDRESS` says
  otherwise. The image listens on all of its own interfaces, and the README's
  `docker run` publishes every port on the host's `127.0.0.1` only.
- The admin API needs a token.
  - **Requests:** every admin request must send `Authorization: Bearer` with
    `TOLLGATE_ADMIN_TOKEN`, which must be at least 32 characters.
  - **Without the token** the admin API is off and answers `503`.
  - **Effect:** web pages can no longer reach it through the browser.
- Budgets survive restarts. At startup, Tollgate replays the current period
  from the audit trail.
  - **Finished calls:** each is charged its settled cost.
  - **Calls with no outcome:** these were cut off by a crash, and are charged
    their full reservation.
  - **A damaged trail:** a line torn by a crash is skipped. Any other
    unreadable line stops startup and points to `audit verify`.
- Streaming on both the OpenAI chat completions and Anthropic messages
  endpoints.
  - **Relaying:** events are relayed as they arrive, with your model's name.
  - **Usage:** Tollgate asks the provider for usage, settles from it, and
    hides the usage chunk from clients that did not ask for it.
  - **Limits:** a gap limit, `tollgate.max-stream-duration` (10 minutes by
    default) and the response size cap end a stream with a final error
    event.
  - **Clients that leave:** the provider is cancelled.
  - **Audit:** streams record `interrupted` or `client_closed`.
