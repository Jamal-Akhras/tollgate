# What works today

Everything here is built and tested in 0.1.0. What comes next is on the [roadmap](../README.md#roadmap).

- **Your SDKs, unchanged.** Tollgate serves the OpenAI chat completions API and
  the Anthropic messages API, so existing SDKs connect by changing their base
  URL. Requests are forwarded in their own format, so structured output, tool
  use and prompt caching keep working
  ([ADR 0005](adr/0005-forward-requests-in-their-own-format.md)).
- **Client libraries.** `tollgate-client` gives you the official OpenAI or
  Anthropic client already pointed at Tollgate, with the headers, the receipt
  of a call and the reason for a refusal as small functions. There is one
  for Python (`pip install "tollgate-client[openai]"`), TypeScript
  (`npm install tollgate-client openai`) and Java
  (`io.github.jamal-akhras:tollgate-client`). You don't need them: the
  base URL above works on its own
  ([ADR 0031](adr/0031-client-libraries-wrap-the-official-sdks.md),
  [ADR 0033](adr/0033-publish-the-client-libraries.md)).
- **Inside your Spring Boot application.** The same gateway runs inside a
  Spring Boot 4.1 application through `tollgate-spring-boot-starter`, with one
  dependency and a config file ([embedding](spring-boot-starter.md)).
- **Coding agents too.** Claude Code works through Tollgate with every
  request reviewed: point `ANTHROPIC_BASE_URL` at Tollgate and give it a
  tenant key ([setup](clients/claude-code.md),
  [ADR 0020](adr/0020-agent-clients-and-skills.md)).
- **Calls per person.** A tenant's `userHeader` names the request header that
  says who is calling, such as `X-OpenWebUI-User-Id`. Its value is recorded in
  the audit trail as an HMAC, so usage can be summed by user without storing
  who they are. Any OpenAI-compatible client that can send a header can use
  it. `tollgate init` sets `userHeader: X-Tollgate-User` on its tenant
  ([ADR 0023](adr/0023-attribute-calls-to-a-user.md)). A tested Open WebUI
  setup guide is planned.
- **Tenants and keys.** Each application gets a tenant with its own API keys,
  which are stored only as SHA-256 hashes, and its own list of models.
- **Data classes and regions.** Each tenant has a default data class, such as
  `internal` or `confidential`. A request may make its class stricter, never
  looser, and each class may go only to providers in the regions you allow.
  Guards fail closed.
- **Refuse tools by name.** A `tool-names` guard refuses a request that
  defines a tool you list, such as `run_command` or `delete_*`, or any tool
  not on an `allow` list. It sees tool definitions, not tools written into a
  prompt ([ADR 0024](adr/0024-refuse-tools-by-name.md)).
- **Try a guard before it refuses.** A guard with `mode: monitor` runs on
  real traffic and records what it would have refused, in the audit trail and
  the `tollgate.guards.monitored` metric, without refusing anything
  ([ADR 0022](adr/0022-shadow-mode.md)).
- **Errors you can act on.** Every error Tollgate writes carries an
  `X-Tollgate-Error` header with its code, such as `policy_denied`,
  `budget_exceeded` or `rate_limited`, on both endpoints, so a program
  doesn't have to read the message. A spent budget also says
  `x-should-retry: false`, so the SDKs don't send the call again.
  `GET /anthropic/v1/models` lists a tenant's Anthropic models
  ([ADR 0032](adr/0032-say-the-error-code-in-a-header.md)).
- **Streaming.** Streamed answers are relayed event by event as they arrive,
  with the same policies, budgets and audit as other calls
  ([ADR 0010](adr/0010-streaming.md)).
- **Only reviewed requests leave.** Fields, tools and content are checked
  against an allowlist, so a request cannot ask the provider to fetch URLs,
  call other servers or keep the conversation. A policy can allow a reviewed
  feature, such as images by URL, for named data classes only
  ([ADR 0008](adr/0008-harden-the-request-path.md)).
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
  ([ADR 0012](adr/0012-redact-personal-data.md),
  [ADR 0013](adr/0013-custom-detectors.md)).
- **Budgets with exact money.** The most a call can cost is reserved before
  the provider is called, and the reservation is settled from the provider's
  reported token usage. Near the limit, the answer is shortened to fit rather
  than refused, and the `X-Tollgate-Output-Limited` header says so. Spending
  is rebuilt from the audit trail when Tollgate starts, so a restart never
  resets a budget. Amounts
  are integer nano-units, never floating point
  ([ADR 0003](adr/0003-money-representation.md),
  [ADR 0006](adr/0006-budget-reservations.md)).
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
  - Nothing is fetched at runtime ([ADR 0009](adr/0009-price-catalog.md)).
- **Costs where you can see them.** A tenant with `showCosts: true` gets
  `X-Tollgate-Cost-Max`, the most a call could cost, and `X-Tollgate-Cost`,
  what it was charged, on every response. A stream ends with the charge as a
  comment line that clients ignore. Add `X-Tollgate-Dry-Run: true` to get the
  estimate, after every policy and budget check, without calling the model
  or spending anything ([ADR 0011](adr/0011-report-costs.md)).
- **Limits per tenant.** `limits: { requestsPerMinute: 60, tokensPerMinute:
  200000, concurrent: 8 }` stops one application from starving the others or
  a local GPU. A tenant over a limit gets `429` with `Retry-After`, which the
  OpenAI and Anthropic SDKs already retry. Each Tollgate instance enforces the
  limits on its own for now
  ([ADR 0015](adr/0015-tenant-rate-limits.md)).
- **Fallback and limits per provider**
  ([ADR 0016](adr/0016-provider-fallback.md)).
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
  - `audit verify` checks a trail end to end and names the batch that no
    longer matches
    ([ADR 0007](adr/0007-audit-trail.md)).
- **Reloads without restarts.** Change `tollgate.yaml` and call the admin API.
  An invalid file never replaces the running config, and every audit record
  names the config version that decided it.
- **A console.** Open `http://127.0.0.1:8081/console/` to see calls flow from
  tenants through their rules to providers, with live numbers, the audit trail
  drawn as Merkle trees, and the running config. It can also make four edits
  ([the console](console.md)).
- **A minimal, non-root image** on a distroless Java 25 base, with no shell
  and no package manager.
