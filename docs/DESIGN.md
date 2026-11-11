# Tollgate design

Status: describes 0.1.0. What is decided but not built yet sits under
"Planned" in each section. Decisions with lasting consequences are recorded in
[`docs/adr`](adr/).

## 1. Purpose

Tollgate sits between applications and LLM providers. Every request passes
through a policy pipeline that decides whether it may go, where it may go, what
it may contain and what it may cost, and every decision is recorded in an audit
trail that can be proven complete.

It is built for organizations that have to answer four questions about any
model call: what was sent, who sent it, what it cost, and why it was allowed.

### Goals

- **Any language can use it.** The server speaks the OpenAI and Anthropic wire
  formats, so existing SDKs work by changing their base URL.
- **Safe defaults, explicit trade-offs.** Security guards fail closed, and
  nothing falls back silently.
- **Policy without code.** Guards, data classes, redaction and budgets are
  configuration.
- **Provable records.** Exact money, a tamper-evident audit trail, and a
  decision trace for every request.
- **Java applications can embed it,** through a Spring Boot starter
  ([ADR 0021](adr/0021-spring-boot-starter.md)). It still serves HTTP, on the
  application's own port.

### Non-goals

- An agent framework, prompt management, or an evaluation platform.
- Currency conversion: one currency per deployment.
- The lowest possible proxy latency at any cost. Tollgate targets low,
  measured overhead; governance comes first.

### Planned

- **Code when configuration isn't enough:** custom Java guards, and webhook
  guards in any language.

## 2. Modules

| Module | Purpose |
|---|---|
| `tollgate-core` | Framework-free Java 21: request model, policy pipeline, decision trace, money. May depend only on the JDK and JSpecify ([ADR 0002](adr/0002-module-boundaries.md)). |
| `tollgate-providers` | Provider wire formats on the JDK HTTP client: OpenAI (also used by any OpenAI-compatible server, such as Ollama, vLLM, LM Studio or Gemini's compatible endpoint) and Anthropic. |
| `tollgate-spring-boot-starter` | The gateway as Spring Boot 4.1 auto-configuration on Spring MVC, Java 21: the endpoints, the request pipeline, budgets, the audit sink and the metrics ([ADR 0021](adr/0021-spring-boot-starter.md)). |
| `tollgate-server` | Standalone server on the starter: the admin API on its own port, the CLI and the image. Spring MVC on virtual threads, JDK 25. |

Commands ship inside the server jar, so the one download does both:
`init`, `keys create --tenant <name>`, `audit keys create`, `audit verify`,
`prices show`, `prices pin` and `usage`.

### Ports

The server listens on three ports, so each can be exposed to a different
audience:

| Port (default) | Serves | Audience |
|---|---|---|
| 8080 | Gateway API | Applications |
| 8081 | Admin API and its OpenAPI document | Operators, on a private network. Bound to `127.0.0.1` unless `tollgate.admin.address` says otherwise |
| 9090 | Health probes and metrics | Orchestrators and scrapers |

Admin routes answer only on the admin port and gateway routes only off it;
anything else is a 404. The check runs on the decoded, normalized path the
handlers are matched against, so encoded or dot-segment variants cannot reach
an admin handler from the gateway port.

### Planned

| Module | Purpose |
|---|---|
| `tollgate-studio` | Admin UI on the admin port, server-rendered with JTE and htmx ([ADR 0004](adr/0004-studio-and-admin-api.md)) |
| `tollgate-testkit` | Conformance suites for custom guards and provider adapters |

Modules are added when they gain code.

## 3. Request lifecycle

1. **Authenticate.** Look the API key up by its short ID and compare hashes in
   constant time. Keys are stored only as hashes and shown once at creation.
   A tenant over its request, token or concurrency limit is refused here,
   before the body is read ([ADR 0015](adr/0015-tenant-rate-limits.md)).
2. **Resolve.** Find the tenant and the data class. The tenant's default data
   class applies; a request header may make it stricter, never looser.
3. **Pin the policy.** Choose the route's policy and record the config version
   that will decide this request.
4. **Pre-call guards,** in order:
   - the built-in check that the request asks only for reviewed fields, tools
     and content ([ADR 0008](adr/0008-harden-the-request-path.md))
   - size limits
   - data class and region rules
   - personal data: refuse or redact patterned values
     ([ADR 0012](adr/0012-redact-personal-data.md))

   The tenant's model list was already checked in step 2.
5. **Reserve budget.** Reserve the most the call can cost. The
   request's size in bytes bounds its input tokens. The gateway lowers
   `max_tokens` to the model's cap, and fills it in only when the tenant has a
   budget, so a budgeted call's output is bounded too. Without a budget, a
   request that sends no `max_tokens` reaches the provider without one.
6. **Audit intent.** Record the decision before any provider is called.
7. **Call the provider,** with a timeout, a per-provider concurrency limit and
   a circuit breaker. Fall back to another provider only before the first byte
   reaches the client ([ADR 0016](adr/0016-provider-fallback.md)).
8. **Restore** redacted values in the response, including streams. The
   mapping lives in memory for that request only.
9. **Settle and audit outcome.** Settle the cost from the provider's reported
   token usage, release the unused reservation, and record the outcome.

Every request produces a **decision trace**: each guard, its verdict, its
duration and the config version. The trace feeds the audit trail and the
metrics, and its ID comes back in the `X-Tollgate-Trace-Id` header.

### Planned

- **Custom guards** after the built-in ones in step 4.
- **Post-call guards** between steps 7 and 8: personal data in the answer, and
  structural validation.

## 4. Configuration

YAML is the source of truth. Tollgate reads its own `tollgate.yaml` rather
than Spring properties, so the server, the starter and the CLI load the same
model the same way.

```yaml
currency: USD
dataClasses: [public, internal, confidential, restricted]   # least to most strict

providers:
  anthropic: { format: anthropic, baseUrl: https://api.anthropic.com, apiKey: "${env:ANTHROPIC_API_KEY}", region: us }
  ollama:    { format: openai, baseUrl: http://gpu-box:11434/v1, region: local, concurrent: 2 }

models:
  claude-sonnet:
    provider: anthropic
    upstream: claude-sonnet-4-5
    price: { input: 3.00, output: 15.00, cacheRead: 0.30, cacheWrite: 3.75 }   # per million tokens
    maxOutputTokens: { default: 2048, cap: 8192 }
  qwen3:
    provider: ollama
    upstream: qwen3:32b
    price: free
    maxOutputTokens: { default: 4096, cap: 16384 }

tenants:
  support-bot:
    dataClass: confidential          # default; headers can only make it stricter
    models: [claude-sonnet, qwen3]
    policy: default
    budget: { limit: 50.00, period: monthly }
    limits: { requestsPerMinute: 60, tokensPerMinute: 200000, concurrent: 8 }

keys:
  - { id: k7Qm2xPa9bCd, tenant: support-bot, sha256: 3f1c…9ab2 }

policies:
  default:
    guards:
      - type: request-size
        maxBytes: 1048576
      - type: data-class
        regions: { public: [us, eu, local], internal: [us, eu, local], confidential: [local], restricted: [local] }
    redact:                        # replaced before the call, restored in the answer
      email: [internal, confidential]
      iban: [internal, confidential, restricted]
      customer-id: [internal, confidential, restricted]
    refuse:                        # never sent
      secret: [public, internal, confidential, restricted]
      codenames: [public, internal, confidential, restricted]

detectors:                         # your own, next to the built-in ones
  customer-id: { pattern: "CUST-[0-9]{6}" }
  codenames: { terms: [Bluebird, Project Falcon] }

audit:                             # required; 'mode: off' must be written out
  mode: sync                       # sync | async | off
  directory: audit
  publicKey: MCowBQYDK2VwAyEA…
  signingKey: ${env:TOLLGATE_AUDIT_SIGNING_KEY}
  hmacKey: ${env:TOLLGATE_AUDIT_HMAC_KEY}
```

- **Tenants** may call only the models they list; any other model is reported as
  not found. Once `dataClasses` are defined, every tenant needs a default
  class, and a request header may move it to a stricter class only.
- **Guards** are `request-size` and `data-class`. The data-class guard fails
  closed: a class with no listed regions, or a provider with no region, is
  refused.
- **Shadow mode:** a guard with `mode: monitor` records what it would have
  refused, in the audit trail and the `tollgate.guards.monitored` metric,
  without refusing, so a new guard can be measured before it is enforced
  ([ADR 0022](adr/0022-shadow-mode.md)).
- **Models** are the names applications ask for. Each maps to one provider,
  the provider's own model name, a price and an output-token cap. Every model
  has a known price before its first request. The price comes from the config,
  or from the reviewed catalog that ships with each release
  ([ADR 0009](adr/0009-price-catalog.md)). Nothing is fetched at runtime.
  A price can also give reasoning tokens and hour-long cache writes their own
  rates, and list long-context tiers that apply once a prompt reaches a size.
- **Secrets** are references, never values, and are never logged:
  `${env:NAME}`.
- **Tenant keys** never appear in configuration; only their hashes do.
  `tollgate keys create` prints a new key once, with the line to add.
- **Reloads** validate the new config and swap it in atomically, through
  `POST /admin/v1/config/reload`. An invalid config never replaces a running
  one, and each decision records the config version that made it.

### Planned

- **Rule guards** in CEL, the Common Expression Language: fast, side-effect
  free, guaranteed to terminate, and type-checked when the config loads.
- **Secrets** from Vault and AWS Secrets Manager.
- **In a cluster,** every instance converging on the latest config.

## 5. Extending Tollgate

`tollgate-core` defines `BudgetStore` and `AuditSink`. The server uses the
in-memory budget store and the JSON-lines audit sink; there is no way yet to
plug in your own without changing the server.

### Planned

| Need | Mechanism |
|---|---|
| Simple conditions | `rule` guards in CEL |
| Custom logic in Java | Implement `Guard`; discovered through `ServiceLoader` or as Spring beans |
| Custom logic in any language | `webhook` guard: an HTTPS endpoint returning allow, deny or redact |
| New provider | Implement a provider adapter; verify it with the testkit's conformance suite |
| Storage | Your own `BudgetStore` or `AuditSink`, as Spring beans through the starter |

Each guard will declare its order, timeout and failure mode. Webhook guards
receive prompt content, so only administrators will be able to configure them;
they will require an allowlisted HTTPS endpoint, optionally mTLS, and every
call will be audited.

## 6. Using Tollgate from other languages

Applications point their existing SDK at the gateway:

```python
openai = OpenAI(base_url="https://tollgate.internal/v1", api_key=os.environ["TOLLGATE_KEY"])
openai.chat.completions.create(
    model="qwen3",
    messages=[...],
    extra_headers={"X-Tollgate-Data-Class": "restricted"},
)

anthropic = Anthropic(base_url="https://tollgate.internal/anthropic", api_key=os.environ["TOLLGATE_KEY"])
anthropic.messages.create(model="claude-sonnet", max_tokens=1024, messages=[...])
```

- Tollgate forwards the original request body and changes only `model` and
  `max_tokens`, so provider features such as structured output, tool use and
  prompt caching keep working. A model is served on the endpoint of its
  provider's format; Tollgate does not translate between formats
  ([ADR 0005](adr/0005-forward-requests-in-their-own-format.md)).
- `GET /v1/models` lists the models the calling tenant may use.
- A blocked request returns a `403` with code `policy_denied` and a reason, in
  the error format of the endpoint that was called, so existing error handling
  works unchanged. The `X-Tollgate-Trace-Id` header carries the decision trace
  ID.
- Coding agents work too: Claude Code is recorded and replayed in the build
  ([setup](clients/claude-code.md),
  [ADR 0020](adr/0020-agent-clients-and-skills.md)).
- Deployed on the same host or as a sidecar, the extra hop is small. The first
  performance report, in 0.1.1, publishes the measured figure.

## 7. Storage

Policy, budgets, limits and reservations live in memory. Budgets are rebuilt
from the audit trail at startup, so a restart never resets them. The audit
trail is a JSON-lines file.

### Planned

| Tier | Policy and ledger | Limits and reservations | Audit |
|---|---|---|---|
| Production | Postgres | Redis (atomic Lua scripts) | Postgres, Kafka, or S3 with Object Lock |

If Redis is unavailable, each policy will choose: fail closed, or fall back to
per-instance limits (the shared limit divided by the instance count).

## 8. Money

Amounts are `long` nano-units of one currency, with `BigDecimal` only at the
edges ([ADR 0003](adr/0003-money-representation.md)). Token costs round up to
the next nano-unit, so a reservation never under-reserves. How reservations
are sized and settled is in [ADR 0006](adr/0006-budget-reservations.md). Near
the limit, the output cap is lowered so the worst case fits
([ADR 0008](adr/0008-harden-the-request-path.md)). A request that cannot fit
even then is refused with a `429`, as OpenAI does for an exhausted quota.
Settled costs use the provider's reported token counts. For streamed
responses, the gateway asks the provider to include usage, so streamed calls
settle exactly too.

## 9. Audit

- **Two records per request:** intent before the provider call, outcome after.
- **Tamper evidence:** records are written in batches. Each batch carries a
  Merkle root that includes the previous batch's root, and each root is
  signed with Ed25519, so a missing or altered record is detectable without
  serializing every write behind one chain. Records are hashed from a fixed
  binary encoding rather than their JSON text, so verification does not
  depend on JSON formatting. `tollgate audit verify` checks a trail end to end.
- **Privacy by default:** prompts and answers are stored only as keyed hashes
  (HMAC), which cannot be reversed by guessing.
- **Durability modes:** synchronous, the default (durable before the provider
  is called, group-committed), or asynchronous (batched). If the intent record
  cannot be written, the request is refused.

The record format, the hashing and the failure rules are in
[ADR 0007](adr/0007-audit-trail.md).

### Planned

- **Storing content,** opt-in and with retention, encrypted with a key per
  tenant, so deleting the key makes that tenant's content unreadable without
  breaking the chain.
- **Performance for each durability mode,** in the first performance report.

## 10. Streaming

- Streams are relayed event by event as they arrive, with the same policies,
  budgets and audit as other calls ([ADR 0010](adr/0010-streaming.md)).
- Restoring redacted values holds back at most one placeholder's length of
  text, so a placeholder split across events is still restored
  ([ADR 0012](adr/0012-redact-personal-data.md)).
- Retries and provider fallback happen only before the first byte is sent.

### Planned

- **Output guards on streams,** with a hold-back window, and strict policies
  that buffer the whole response.

## 11. Policy Studio (planned)

A small admin UI on the admin port, decided in
[ADR 0004](adr/0004-studio-and-admin-api.md) and not built yet.

- Tenants and keys: create, rotate, revoke; a new key is shown once.
- Guards: reorder, switch between enforce and shadow, pick redaction entities.
- Budgets and limits, previewed in plain terms ("about N calls per day").
- **Simulator:** paste a request and step through its decision trace.
- Live view: blocks, spend per tenant, and what shadow rules would have done.

Two modes: **GitOps**, where configuration lives in Git and Studio is read-only
plus the simulator, exporting changes as YAML diffs; and **managed**, where
configuration is versioned in Postgres with maker-checker approval, diffs and
rollback. Sign-in uses OIDC with viewer, editor and approver roles.

Studio will be one client of the admin API. The API's OpenAPI contract is
already published in [`docs/api/admin-openapi.json`](api/admin-openapi.json)
and checked against the code in CI, so other tools can build on it today.

## 12. Quality bar

| Area | Practice |
|---|---|
| Correctness | Immutable records, sealed result types, no `null` in public interfaces (NullAway at compile time), injected clock |
| Static analysis | Error Prone and NullAway in every compile; `-Xlint:all -Werror`; CodeQL |
| Formatting | Spotless with palantir-java-format, checked in CI |
| Tests | JUnit, jqwik property tests against reference implementations, a fake provider for HTTP-level tests, recorded agent-client sessions, ArchUnit module rules |
| Coverage | JaCoCo floors per module; PIT mutation testing on core |
| Supply chain | Actions pinned to commits, Dependabot, CycloneDX SBOM, build provenance attested for each image, OpenSSF Scorecard |
| Observability | OpenTelemetry traces (GenAI conventions, still at development status), Micrometer metrics for Prometheus, a Grafana dashboard |
| Deployment | Distroless image |

### Planned

| Area | Practice |
|---|---|
| Correctness | Idempotency keys on admin writes |
| Tests | Testcontainers, WireMock provider fixtures, Toxiproxy fault injection |
| Performance | JMH microbenchmarks for hot paths and Gatling load tests, published in 0.1.1; regression thresholds and JFR allocation profiling |
| Compatibility | japicmp on the extension interfaces from the first release |
| Supply chain | Signed images |
| Observability | JSON logs with redaction |
| Deployment | Helm chart, Terraform example |

Performance target, to be measured rather than assumed: under 2 ms of gateway
overhead at p99 for non-streaming calls in asynchronous audit mode, published
with hardware details for every audit mode.

## 13. Roadmap

| Release | Scope |
|---|---|
| **0.1.0** | OpenAI chat completions and Anthropic messages, streamed or not, forwarded in their own format to OpenAI, Anthropic and any OpenAI-compatible server; `/v1/models`; tenants, hashed keys and per-tenant model lists; data classes with the size and data-class guards, and shadow mode for them; a Spring Boot starter that embeds the gateway; the request review of [ADR 0008](adr/0008-harden-the-request-path.md); redaction and restore of patterned personal data and detectors of your own, streams included; budget reservations priced from a reviewed catalog ([ADR 0009](adr/0009-price-catalog.md)); per-tenant rate limits; provider concurrency limits, fallback and circuit breakers; a signed, batch-chained audit trail with `audit verify` and a usage report; config reload; Prometheus metrics, OpenTelemetry traces and a Grafana dashboard; Claude Code through the review; a Docker image; `tollgate-core`, `tollgate-providers` and `tollgate-spring-boot-starter` on Maven Central |
| **0.1.1** | The first performance report: JMH microbenchmarks and Gatling load tests for each audit mode, with the hardware they ran on |
| **Next** | The Responses API and embeddings, for Codex ([ADR 0018](adr/0018-responses-and-embeddings.md)); MCP tool calls through Tollgate ([ADR 0019](adr/0019-mcp-through-tollgate.md)); GitHub Copilot and Gemini CLI ([ADR 0020](adr/0020-agent-clients-and-skills.md)); audit records for tool calls and sessions |
| **Later** | Output guards with a hold-back window on streams; name detection; CEL rules; webhook guards; the testkit; Postgres and Redis storage; Policy Studio ([ADR 0004](adr/0004-studio-and-admin-api.md)); cluster config sync; Azure OpenAI and Bedrock; a Helm chart and a Terraform example; semantic caching; a Python helper package |

## 14. Risks

- **Scope.** The non-goals and milestone boundaries are the defence.
- **Provider API churn.** Recorded fixtures catch changes in tests. A field
  Tollgate does not know is refused with a message that names it, never
  dropped silently ([ADR 0008](adr/0008-harden-the-request-path.md)).
- **Streaming with redaction** is the hardest part technically and is
  documented as a trade-off.
