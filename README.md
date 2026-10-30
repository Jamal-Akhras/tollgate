# Tollgate

[![ci](https://github.com/Jamal-Akhras/tollgate/actions/workflows/ci.yml/badge.svg)](https://github.com/Jamal-Akhras/tollgate/actions/workflows/ci.yml)
[![codeql](https://github.com/Jamal-Akhras/tollgate/actions/workflows/codeql.yml/badge.svg)](https://github.com/Jamal-Akhras/tollgate/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/Jamal-Akhras/tollgate/badge)](https://scorecard.dev/viewer/?uri=github.com/Jamal-Akhras/tollgate)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

**A policy and audit gateway for LLM traffic.** Tollgate sits between your
applications and model providers, and decides, for every request, whether it
may go, where it may go, what it may contain and what it may cost. It records
each decision in an audit trail you can prove is complete.

> **Status: early development.** The build, quality gates and server skeleton
> are in place; the gateway itself is being built. See the
> [roadmap](docs/DESIGN.md#13-roadmap).

## Why

Teams adopting LLMs end up rebuilding the same controls in every application:
which data may leave, which models are allowed, how much each team may spend,
and a record of what happened. Tollgate is being built to put those controls
in one place:

- **Works with any language.** It speaks the OpenAI and Anthropic APIs, so
  existing SDKs connect by changing their base URL.
- **Embeds in Java services** through a Spring Boot starter, from the same
  framework-free core.
- **Policy without code, code when needed:** rules in configuration, custom
  Java guards, or a webhook in any language.
- **Governance built in:** data classes that can only be tightened, provider
  region rules, shadow mode for new policies, maker-checker approval for
  changes, and guards that fail closed.
- **Exact money:** budgets reserve before the call and settle from reported
  usage, in integer nano-units.
- **Tamper-evident audit:** batch-chained, signed, and private by default.

## How applications will use it

```python
from anthropic import Anthropic
from openai import OpenAI

openai = OpenAI(base_url="https://tollgate.internal/v1", api_key=TOLLGATE_KEY)
openai.chat.completions.create(model="qwen3", messages=[...])

anthropic = Anthropic(base_url="https://tollgate.internal/anthropic", api_key=TOLLGATE_KEY)
anthropic.messages.create(model="claude-sonnet", max_tokens=1024, messages=[...])
```

Requests are forwarded in their own format, so structured output, tool use and
prompt caching keep working. A blocked request returns a `403` with code
`policy_denied` and the reason, in the SDK's own error format, so existing
error handling keeps working.

## Building

Requires JDK 25. The Maven Wrapper downloads and verifies Maven itself.

```bash
./mvnw verify                          # format check, static analysis, tests, coverage, API contract
./mvnw -pl tollgate-core -Pmutation verify   # mutation testing on core
./mvnw -Psbom package                  # CycloneDX SBOM in target/bom.json
```

Run the server:

```bash
java -jar tollgate-server/target/tollgate-server-*.jar
```

| Port | Serves |
|---|---|
| 8080 | Gateway API, for applications |
| 8081 | Admin API and its OpenAPI document, for operators. Loopback only by default; set `TOLLGATE_ADMIN_ADDRESS=0.0.0.0` to expose it, for example in a container |
| 9090 | Health probes, for orchestrators |

## Documentation

- [Design](docs/DESIGN.md): architecture, request lifecycle, configuration and
  roadmap
- [Architecture decision records](docs/adr/)
- [Admin API contract](docs/api/admin-openapi.json)
- [Contributing](CONTRIBUTING.md) · [Security policy](SECURITY.md) ·
  [Changelog](CHANGELOG.md)

## License

[Apache License 2.0](LICENSE).
