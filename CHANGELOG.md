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
- Request size and data-class guards. Data classes can only be made stricter,
  and each class goes only to the regions it allows.
- Per-tenant budgets: the worst case is reserved before the call, settled from
  reported usage, and over-budget requests are refused with `429`.
- A signed, batch-chained audit trail. Records are hashed from a fixed binary
  encoding into RFC 6962 Merkle trees. Each root is chained to the one before
  and signed with Ed25519, and prompts are kept only as HMACs. Writes are
  synchronous with group commit, or asynchronous. A request whose intent
  cannot be recorded is refused.
- `audit keys create` and `audit verify`.
- `POST /admin/v1/config/reload`, which validates the file and swaps it in
  atomically.
- A container image on a distroless Java 25 base, built with Jib.

### Security

- Every `/v3/api-docs` path, including the YAML document, is served only on the
  admin port.
- A config is rejected if any tenant's policy has no data-class guard, so a
  tenant's data class can never go unenforced.
- Requests are checked against an allowlist before they are forwarded, and the
  refusal is audited. Tollgate refuses:
  - fields it has not reviewed, and `anthropic-beta` headers
  - provider-side tools such as web search, web fetch and code execution
  - fields that send data elsewhere or change pricing, such as `mcp_servers`
    and `store: true`
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
