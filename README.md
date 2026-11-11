# Tollgate

[![ci](https://github.com/Jamal-Akhras/tollgate/actions/workflows/ci.yml/badge.svg)](https://github.com/Jamal-Akhras/tollgate/actions/workflows/ci.yml)
[![codeql](https://github.com/Jamal-Akhras/tollgate/actions/workflows/codeql.yml/badge.svg)](https://github.com/Jamal-Akhras/tollgate/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/Jamal-Akhras/tollgate/badge)](https://scorecard.dev/viewer/?uri=github.com/Jamal-Akhras/tollgate)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

**A policy and audit gateway for LLM traffic.** Tollgate sits between your
applications and model providers. For every request it decides whether it may
go, where it may go and what it may cost, and it records that decision in a
signed audit trail that shows when a record has been changed or removed.

It is built for teams that have to answer four questions about any model call:
what was sent, who sent it, what it cost, and why it was allowed.

> **Status: preparing 0.1.0.** Everything under "What works today" is built
> and tested. What comes next is on the [roadmap](#roadmap).

## What works today

- **Your SDKs, unchanged.** Tollgate serves the OpenAI chat completions API and
  the Anthropic messages API, so existing SDKs connect by changing their base
  URL. Requests are forwarded in their own format, so structured output, tool
  use and prompt caching keep working
  ([ADR 0005](docs/adr/0005-forward-requests-in-their-own-format.md)).
- **Inside your Spring Boot application.** The same gateway runs inside a
  Spring Boot 4.1 application through `tollgate-spring-boot-starter`, with one
  dependency and a config file ([embedding](#inside-a-spring-boot-application)).
- **Coding agents too.** Claude Code works through Tollgate with every
  request reviewed: point `ANTHROPIC_BASE_URL` at Tollgate and give it a
  tenant key ([setup](docs/clients/claude-code.md),
  [ADR 0020](docs/adr/0020-agent-clients-and-skills.md)).
- **Tenants and keys.** Each application gets a tenant with its own API keys,
  which are stored only as SHA-256 hashes, and its own list of models.
- **Data classes and regions.** Each tenant has a default data class, such as
  `internal` or `confidential`. A request may make its class stricter, never
  looser, and each class may go only to providers in the regions you allow.
  Guards fail closed.
- **Try a guard before it refuses.** A guard with `mode: monitor` runs on
  real traffic and records what it would have refused, in the audit trail and
  the `tollgate.guards.monitored` metric, without refusing anything
  ([ADR 0022](docs/adr/0022-shadow-mode.md)).
- **Streaming.** Streamed answers are relayed event by event as they arrive,
  with the same policies, budgets and audit as other calls
  ([ADR 0010](docs/adr/0010-streaming.md)).
- **Only reviewed requests leave.** Fields, tools and content are checked
  against an allowlist, so a request cannot ask the provider to fetch URLs,
  call other servers or keep the conversation. A policy can allow a reviewed
  feature, such as images by URL, for named data classes only
  ([ADR 0008](docs/adr/0008-harden-the-request-path.md)).
- **Personal data stays home.** A policy can replace email addresses, card
  numbers, IBANs, phone numbers, national ID numbers and IP addresses with
  placeholders such as `[EMAIL_1]` before a request leaves, and put the real
  values back in the answer, in tool-call arguments and in streams. It can
  refuse API keys and private keys outright. You can add detectors of your
  own, as a pattern such as `CUST-[0-9]{6}` or a list of terms such as client
  names, and redact or refuse them the same way. The audit trail counts what
  was replaced and proves what was sent, without recording any value.
  Detection is by pattern and checksum, so a value written in words or a key
  with an unknown prefix gets through
  ([ADR 0012](docs/adr/0012-redact-personal-data.md),
  [ADR 0013](docs/adr/0013-custom-detectors.md)).
- **Budgets with exact money.** The most a call can cost is reserved before
  the provider is called, and the reservation is settled from the provider's
  reported token usage. Near the limit, the answer is shortened to fit rather
  than refused, and the `X-Tollgate-Output-Limited` header says so. Spending
  is rebuilt from the audit trail when Tollgate starts, so a restart never
  resets a budget. Amounts
  are integer nano-units, never floating point
  ([ADR 0003](docs/adr/0003-money-representation.md),
  [ADR 0006](docs/adr/0006-budget-reservations.md)).
- **Prices you don't have to type.**
  - **Hosted models:** a reviewed catalog generated from
    [models.dev](https://models.dev) ships with each release. Models on
    OpenAI, Anthropic, DashScope, OpenRouter and other known hosts take their
    price from it, including long-context tiers and reasoning rates.
  - **Other providers:** a provider can name a catalog with `catalog:`.
  - **Local models:** set `price: free`.
  - **Cache writes kept for an hour** are charged at `cacheWrite1h`, which is
    twice the input price on Anthropic unless you set it.
  - **Your own rates:** a `price` you write always wins. `tollgate prices pin
    tollgate.yaml` prints the catalog prices your config uses, ready to paste
    in, and `tollgate prices show openai gpt-6.1-sol` prints one.
  - Nothing is fetched at runtime ([ADR 0009](docs/adr/0009-price-catalog.md)).
- **Costs where you can see them.** A tenant with `showCosts: true` gets
  `X-Tollgate-Cost-Max`, the most a call could cost, and `X-Tollgate-Cost`,
  what it was charged, on every response. A stream ends with the charge as a
  comment line that clients ignore. Add `X-Tollgate-Dry-Run: true` to get the
  estimate, after every policy and budget check, without calling the model
  or spending anything ([ADR 0011](docs/adr/0011-report-costs.md)).
- **Limits per tenant.** `limits: { requestsPerMinute: 60, tokensPerMinute:
  200000, concurrent: 8 }` stops one application from starving the others or
  a local GPU. A tenant over a limit gets `429` with `Retry-After`, which the
  OpenAI and Anthropic SDKs already retry. Each Tollgate instance enforces the
  limits on its own for now
  ([ADR 0015](docs/adr/0015-tenant-rate-limits.md)).
- **Fallback and limits per provider**
  ([ADR 0016](docs/adr/0016-provider-fallback.md)).
  - **Fallback:** `fallback: [qwen3-cloud]` on a model names models to try,
    in order, when its provider can't be reached, times out, is busy, or
    answers `401`, `403`, `408`, `429` or `5xx`. Each fallback is checked by
    the tenant's policy again, so confidential data never falls back to a
    region it may not go to. Streams fall back only before the first byte.
  - **Circuit breaker:** after five failures in a row, calls skip a provider
    for 30 seconds instead of waiting for it to time out, then one call
    tries it again.
  - **Limits:** `concurrent: 2` on a provider caps the calls in flight to it
    across all tenants, so a local model is never sent more than it can
    serve. Extra calls go to a fallback, or get `503` `provider_busy` at
    once instead of waiting.
- **A tamper-evident audit trail:**
  - Every decision is recorded before the provider is called, and every
    outcome after.
  - Records are hashed into Merkle trees (RFC 6962). Each tree's root is
    chained to the previous one and signed with Ed25519.
  - Prompts and responses are stored only as keyed hashes (HMAC).
  - `audit verify` checks a trail end to end and names the line of any change
    ([ADR 0007](docs/adr/0007-audit-trail.md)).
- **Reloads without restarts.** Change `tollgate.yaml` and call the admin API.
  An invalid file never replaces the running config, and every audit record
  names the config version that decided it.
- **A minimal, non-root image** on a distroless Java 25 base, with no shell
  and no package manager.

## Quickstart

You need JDK 25, and a model server that speaks the OpenAI API. This example
uses [Ollama](https://ollama.com) on its default port.

**1. Build the server.**

```bash
./mvnw -DskipTests install
alias tollgate='java -XX:+ExitOnOutOfMemoryError -jar tollgate-server/target/tollgate-server-0.1.0-SNAPSHOT.jar'
```

**2. Create a config and its secrets.**

```bash
tollgate init
```

- **`tollgate.yaml`** is ready for Ollama with `qwen3:8b`, and holds no secrets.
- **`tollgate.env`** holds the audit keys and an admin token. Only you can read
  it, and `init` never overwrites it. Keep it out of version control and back
  it up.
- **The API key** for the tenant `my-app` is printed once.

The config it writes:

```yaml
dataClasses: [public, internal, confidential]

providers:
  ollama: { format: openai, baseUrl: "http://localhost:11434/v1", region: local }

models:
  qwen3:
    provider: ollama
    upstream: "qwen3:8b"
    price: free
    maxOutputTokens: { default: 1024, cap: 4096 }

tenants:
  my-app:
    models: [qwen3]
    dataClass: internal
    # budget: { limit: 5.00, period: monthly }  for a priced model
    # limits: { requestsPerMinute: 60, concurrent: 4 }

keys:
  - { id: …, tenant: my-app, sha256: … }

policies:
  default:
    guards:
      - { type: request-size, maxBytes: 1048576 }
      - type: data-class
        regions: { public: [local], internal: [local], confidential: [local] }
    features:                                 # confidential data stays strict
      remote-images: [public, internal]
    redact:                                   # sent as [CARD_1], restored in the answer
      card: [public, internal, confidential]
    refuse:                                   # API keys and private keys never leave
      secret: [public, internal, confidential]

audit:
  mode: sync
  directory: audit
  publicKey: …
  signingKey: ${env:TOLLGATE_AUDIT_SIGNING_KEY}
  hmacKey: ${env:TOLLGATE_AUDIT_HMAC_KEY}
```

**3. Start Tollgate** with the secrets in its environment:

```bash
set -a; . ./tollgate.env; set +a
tollgate
```

**4. Call it** with the OpenAI SDK, using the tenant key:

```python
from openai import OpenAI

client = OpenAI(base_url="http://localhost:8080/v1", api_key="tg_…")
reply = client.chat.completions.create(
    model="qwen3", messages=[{"role": "user", "content": "Say hello"}]
)
print(reply.choices[0].message.content)
```

A request that breaks a rule is refused in the SDK's own error format, with
the reason, and each response carries an `X-Tollgate-Trace-Id` header that
matches its audit records.

**5. Verify the audit trail:**

```console
$ tollgate audit verify --public-key MCowBQYDK2VwAyEA… audit
Checked 2 records in 1 sealed batch.
The last batch is 1, sealed at 2026-11-02T14:03:12.100Z with root 9f2c….
The audit trail is intact.
```

Edit any recorded field and run it again: verification fails and names the
batch that no longer matches.

### With Docker

```bash
./mvnw -DskipTests install
./mvnw -pl tollgate-server jib:dockerBuild -Djib.from.platforms=linux/amd64

docker run --rm ghcr.io/jamal-akhras/tollgate:0.1.0-SNAPSHOT audit keys create
docker run -d --name tollgate \
  -v "$PWD/tollgate.yaml:/etc/tollgate/tollgate.yaml:ro" \
  -v tollgate-data:/home/nonroot \
  -e TOLLGATE_AUDIT_SIGNING_KEY -e TOLLGATE_AUDIT_HMAC_KEY -e TOLLGATE_ADMIN_TOKEN \
  --add-host=host.docker.internal:host-gateway \
  -p 127.0.0.1:8080:8080 -p 127.0.0.1:8081:8081 -p 127.0.0.1:9090:9090 \
  ghcr.io/jamal-akhras/tollgate:0.1.0-SNAPSHOT
```

In the container, set `audit.directory` to `/home/nonroot/audit` so the trail
lands on the volume, and point the provider at `host.docker.internal`. The
`--add-host` line makes that name work on Linux too; Docker Desktop provides
it already.

From the first release, each version is published as
`ghcr.io/jamal-akhras/tollgate:<version>` for amd64 and arm64, with a signed
record of how it was built. Check it before you run it:

```bash
gh attestation verify oci://ghcr.io/jamal-akhras/tollgate:<version> --owner Jamal-Akhras
```

### As a systemd service

```ini
[Unit]
Description=Tollgate, a policy and audit gateway for LLM traffic
Wants=network-online.target
After=network-online.target

[Service]
User=tollgate
StateDirectory=tollgate
EnvironmentFile=/etc/tollgate/tollgate.env
Environment=TOLLGATE_CONFIG=/etc/tollgate/tollgate.yaml
Environment=TOLLGATE_ADDRESS=0.0.0.0
ExecStart=/usr/bin/java -XX:+ExitOnOutOfMemoryError -jar /opt/tollgate/tollgate.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

- **`TOLLGATE_ADDRESS`** is only needed when clients run on other machines or
  in containers, such as Open WebUI. Without it, they get a connection error.
- **`tollgate.env`** holds the audit secrets and the admin token as
  `NAME=value` lines. Make it readable by root only.
- **Set `audit.directory` to `/var/lib/tollgate/audit`.** A relative directory
  is resolved next to the config file, and `/etc/tollgate` is not writable.
- **Start it** with `systemctl enable --now tollgate`. `journalctl -u tollgate`
  shows the address each port listens on.
- **Memory:** each call in flight holds its request and answer, up to
  `tollgate.max-request-size` (16 MB) and `tollgate.max-response-size`
  (32 MB). A slow model lets calls pile up, so give its provider a
  `concurrent` limit, and set `-Xmx` to about half the machine's or
  container's memory. `-XX:+ExitOnOutOfMemoryError` with `Restart=on-failure`
  turns running out into a clean restart.

### Inside a Spring Boot application

Add the starter to a Spring Boot 4.1 application on Spring MVC
([ADR 0021](docs/adr/0021-spring-boot-starter.md)):

```xml
<dependency>
  <groupId>io.github.jamal-akhras</groupId>
  <artifactId>tollgate-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Set `tollgate.config` to a `tollgate.yaml` and give it the audit secrets, as
for the server. The application then serves `/v1/chat/completions`,
`/v1/models` and `/anthropic/v1/messages` with the same guards, budgets,
redaction, audit, metrics and traces.

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
  [the server's `application.yaml`](tollgate-server/src/main/resources/application.yaml).
- **Not supported:** WebFlux applications.

## Operating it

| Port | Serves |
|---|---|
| 8080 | The gateway API, for applications: `/v1/chat/completions`, `/v1/models` and `/anthropic/v1/messages` |
| 8081 | The admin API and its [OpenAPI contract](docs/api/admin-openapi.json), for operators. Loopback only unless `TOLLGATE_ADMIN_ADDRESS` says otherwise, and every call needs the admin token |
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

- **Metrics** ([ADR 0017](docs/adr/0017-metrics-traces-and-usage.md)), for
  each tenant, model and provider:
  - `tollgate_requests_total` by result or refusal code
  - `gen_ai_client_token_usage` by token type, and `tollgate_cost_total`
  - `gen_ai_client_operation_duration_seconds` for each provider call, and
    `tollgate_stream_first_event_seconds` for streams
  - `tollgate_budget_spent` and `tollgate_budget_available`, and
    `tollgate_providers_in_flight`
  - `tollgate_limits_refused_total`, `tollgate_providers_busy_total` and
    `tollgate_fallbacks_total`

  Prompts, answers and key IDs never appear in metrics. To see them, import
  [docs/grafana/tollgate.json](docs/grafana/tollgate.json) into Grafana and
  pick your Prometheus data source: calls, refusals, spend, budgets, tokens,
  provider latency, fallbacks and calls in flight, filtered by tenant, model
  and provider.

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
  - **Requests without the right token** get `401`. A web page cannot send it,
    so a browser cannot be used to call the admin API behind your back.
- **Reload the config:**
  `curl -X POST -H "Authorization: Bearer $TOLLGATE_ADMIN_TOKEN" http://127.0.0.1:8081/admin/v1/config/reload`.
  A broken file returns `422` and leaves the running config in place. A
  change to the `audit` section returns `409`, because it needs a restart.
- **Usage and budgets:**
  `curl -H "Authorization: Bearer $TOLLGATE_ADMIN_TOKEN" "http://127.0.0.1:8081/admin/v1/usage?from=2026-11-01&by=tenant"`
  sums the audit trail: calls, refusals by code, tokens and cost, by
  `tenant`, `model`, `key` or `day`. The numbers are the ones `audit verify`
  vouches for. `java -jar tollgate.jar usage tollgate.yaml --by model` prints
  the same table, and `/admin/v1/budgets` shows each budget's spent, reserved
  and available amounts.
- **Audit modes:**
  - In `sync` mode, the default, each record is fsynced before the request
    moves on, and concurrent requests share fsyncs.
  - `async` mode doesn't wait. It can lose the last records in a crash.
  - If the trail cannot be written, requests are refused with `503`, never
    sent unrecorded.
- **Secrets** are only ever `${env:NAME}` references in the config file.

## Roadmap

| Release | Scope |
|---|---|
| **0.1.0** | Everything under "What works today", with the core, provider and starter libraries on Maven Central |
| **0.1.1** | The first performance report: microbenchmarks and load tests for each audit mode, with the hardware they ran on |
| **Next** | The OpenAI Responses API and embeddings, so Codex works; MCP tool calls through Tollgate; GitHub Copilot and Gemini CLI |
| **Later** | Output guards, name detection, CEL rules and webhook guards; Postgres and Redis storage; Policy Studio; Azure OpenAI and Bedrock; a Helm chart and a Terraform example |

The full plan, and the reasoning behind it, is in [the design](docs/DESIGN.md).

## Building

Requires JDK 25. The Maven Wrapper downloads and verifies Maven itself.

```bash
./mvnw verify                                 # formatting, Error Prone, NullAway, tests, coverage, API contract
./mvnw -pl tollgate-core -Pmutation verify    # mutation testing on core
./mvnw -Psbom package                         # CycloneDX SBOM in target/bom.json
```

The core module depends only on the JDK, compiles for Java 21, and is
mutation tested. The checks the build enforces are listed under the
[quality bar](docs/DESIGN.md#12-quality-bar).

## Documentation

- [Design](docs/DESIGN.md): architecture, request lifecycle, configuration and roadmap
- [Architecture decision records](docs/adr/)
- [Claude Code through Tollgate](docs/clients/claude-code.md)
- [Admin API contract](docs/api/admin-openapi.json)
- [Contributing](CONTRIBUTING.md) · [Security policy](SECURITY.md) · [Changelog](CHANGELOG.md)

## License

[Apache License 2.0](LICENSE).
