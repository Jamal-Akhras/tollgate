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

> **Status: working toward 0.1.** Everything under "What works today" is built
> and tested. The rest of the design is on the [roadmap](#roadmap).

## What works today

- **Your SDKs, unchanged.** Tollgate serves the OpenAI chat completions API and
  the Anthropic messages API, so existing SDKs connect by changing their base
  URL. Requests are forwarded in their own format, so structured output, tool
  use and prompt caching keep working
  ([ADR 0005](docs/adr/0005-forward-requests-in-their-own-format.md)).
- **Tenants and keys.** Each application gets a tenant with its own API keys,
  which are stored only as SHA-256 hashes, and its own list of models.
- **Data classes and regions.** Each tenant has a default data class, such as
  `internal` or `confidential`. A request may make its class stricter, never
  looser, and each class may go only to providers in the regions you allow.
  Guards fail closed.
- **Budgets with exact money.** The most a call can cost is reserved before
  the provider is called, and the reservation is settled from the provider's
  reported token usage. Amounts are integer nano-units, never floating point
  ([ADR 0003](docs/adr/0003-money-representation.md),
  [ADR 0006](docs/adr/0006-budget-reservations.md)).
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
- **A small, non-root image** on a distroless Java 25 base.

## Quickstart

You need JDK 25, and a model server that speaks the OpenAI API. This example
uses [Ollama](https://ollama.com) on its default port.

**1. Build the server.**

```bash
./mvnw -DskipTests install
alias tollgate='java -jar tollgate-server/target/tollgate-server-0.1.0-SNAPSHOT.jar'
```

**2. Create the audit keys and a tenant key.** Each command prints its secrets
once, along with the config to add.

```bash
tollgate audit keys create
tollgate keys create --tenant demo
```

**3. Write `tollgate.yaml`,** filling in the key line, the public key, and the
two secrets as environment variables:

```yaml
dataClasses: [public, internal, confidential]

providers:
  ollama: { format: openai, baseUrl: "http://localhost:11434/v1", region: local }

models:
  qwen3:
    provider: ollama
    upstream: "qwen3:8b"
    price: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }
    maxOutputTokens: { default: 1024, cap: 4096 }

tenants:
  demo:
    models: [qwen3]
    dataClass: internal
    budget: { limit: 5.00, period: monthly }

keys:
  - { id: …, tenant: demo, sha256: … }       # printed by 'keys create'

policies:
  default:
    guards:
      - { type: request-size, maxBytes: 1048576 }
      - type: data-class
        regions: { public: [local], internal: [local], confidential: [local] }

audit:
  mode: sync
  directory: audit
  publicKey: …                                # printed by 'audit keys create'
  signingKey: ${env:TOLLGATE_AUDIT_SIGNING_KEY}
  hmacKey: ${env:TOLLGATE_AUDIT_HMAC_KEY}
```

**4. Start Tollgate** with the two secrets in its environment:

```bash
export TOLLGATE_AUDIT_SIGNING_KEY=… TOLLGATE_AUDIT_HMAC_KEY=…
tollgate
```

**5. Call it** with the OpenAI SDK, using the tenant key:

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

**6. Verify the audit trail:**

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
  -e TOLLGATE_AUDIT_SIGNING_KEY -e TOLLGATE_AUDIT_HMAC_KEY \
  -e TOLLGATE_ADMIN_ADDRESS=0.0.0.0 \
  -p 8080:8080 -p 127.0.0.1:8081:8081 -p 127.0.0.1:9090:9090 \
  ghcr.io/jamal-akhras/tollgate:0.1.0-SNAPSHOT
```

In the container, set `audit.directory` to `/home/nonroot/audit` so the trail
lands on the volume, and point the provider at `host.docker.internal`.

## Operating it

| Port | Serves |
|---|---|
| 8080 | The gateway API, for applications: `/v1/chat/completions`, `/v1/models` and `/anthropic/v1/messages` |
| 8081 | The admin API and its [OpenAPI contract](docs/api/admin-openapi.json), for operators. Loopback only unless `TOLLGATE_ADMIN_ADDRESS` says otherwise |
| 9090 | Health probes. Readiness reports down if the audit trail cannot be written |

- **Reload the config:** `curl -X POST http://127.0.0.1:8081/admin/v1/config/reload`.
  A broken file returns `422` and leaves the running config in place. A
  change to the `audit` section returns `409`, because it needs a restart.
- **Audit modes:**
  - In `sync` mode, the default, each record is fsynced before the request
    moves on, and concurrent requests share fsyncs.
  - `async` mode doesn't wait. It can lose the last records in a crash.
  - If the trail cannot be written, requests are refused with `503`, never
    sent unrecorded.
- **Secrets** are only ever `${env:NAME}` references in the config file.

## Roadmap

| Milestone | Scope |
|---|---|
| **M1** (in progress) | Everything above, the security hardening in [ADR 0008](docs/adr/0008-harden-the-request-path.md), streaming, and a published performance report |
| **M2** | PII redaction and restore, including on streams, CEL rules, webhook guards, Postgres and Redis storage, rate limits, provider fallback, OpenTelemetry, a Spring Boot starter, and a first release to Maven Central |
| **M3** | Policy Studio: a simulator, a guard editor, shadow mode and maker-checker approval |

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
- [Admin API contract](docs/api/admin-openapi.json)
- [Contributing](CONTRIBUTING.md) · [Security policy](SECURITY.md) · [Changelog](CHANGELOG.md)

## License

[Apache License 2.0](LICENSE).
