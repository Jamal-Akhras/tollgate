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
