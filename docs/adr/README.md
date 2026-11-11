# Architecture decision records

Each record explains one decision with lasting consequences: the context, what
was decided, what else was considered, and what follows from it. A record that
is accepted is not always built yet; the last column says what 0.1.0 ships.

| ADR | Decision | Status | In 0.1.0 |
|---|---|---|---|
| [0001](0001-runtime-and-build.md) | Runtime and build | Accepted | Yes |
| [0002](0002-module-boundaries.md) | Module boundaries | Accepted | Yes |
| [0003](0003-money-representation.md) | Money as integer nano-units | Accepted | Yes |
| [0004](0004-studio-and-admin-api.md) | An API-first admin surface with a server-rendered Studio | Accepted | The admin API and its OpenAPI contract; Studio is not built |
| [0005](0005-forward-requests-in-their-own-format.md) | Forward requests in their own format | Accepted | Yes |
| [0006](0006-budget-reservations.md) | Budget reservations | Accepted | Yes |
| [0007](0007-audit-trail.md) | A signed, batch-chained audit trail | Accepted | Yes |
| [0008](0008-harden-the-request-path.md) | Harden the request path | Accepted | Yes |
| [0009](0009-price-catalog.md) | A reviewed price catalog | Accepted | Yes |
| [0010](0010-streaming.md) | Streaming | Accepted | Yes |
| [0011](0011-report-costs.md) | Report costs | Accepted | Yes |
| [0012](0012-redact-personal-data.md) | Redact personal data | Accepted | Yes |
| [0013](0013-custom-detectors.md) | Detectors of your own | Accepted | Yes |
| 0014 | Measure performance | Reserved | No; it arrives with the first performance report in 0.1.1 |
| [0015](0015-tenant-rate-limits.md) | Tenant rate limits | Accepted | Yes |
| [0016](0016-provider-fallback.md) | Provider fallback | Accepted | Yes |
| [0017](0017-metrics-traces-and-usage.md) | Metrics, traces and usage | Accepted | Yes |
| [0018](0018-responses-and-embeddings.md) | The Responses API and embeddings | Accepted | No |
| [0019](0019-mcp-through-tollgate.md) | MCP tool calls through Tollgate | Accepted | No |
| [0020](0020-agent-clients-and-skills.md) | Agent clients and skills | Accepted | Claude Code only |
| [0021](0021-spring-boot-starter.md) | A Spring Boot starter that embeds the gateway | Accepted | No |
| [0022](0022-shadow-mode.md) | Shadow mode for guards | Accepted | Yes |
