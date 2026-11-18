# Architecture decision records

Each record explains one decision with lasting consequences: the context, what
was decided, what else was considered, and what follows from it. A record that
is accepted is not always built yet; the last column says what is built. "Yes"
means in 0.1.0.

| ADR | Decision | Status | Built |
|---|---|---|---|
| [0001](0001-runtime-and-build.md) | Runtime and build | Accepted | Yes |
| [0002](0002-module-boundaries.md) | Module boundaries | Accepted | Yes |
| [0003](0003-money-representation.md) | Money as integer nano-units | Accepted | Yes |
| [0004](0004-studio-and-admin-api.md) | An API-first admin surface with a server-rendered Studio | Accepted, amended | The admin API and its OpenAPI contract. Studio is not built: the console replaces it ([ADR 0025](0025-console-as-a-static-app.md)) |
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
| [0021](0021-spring-boot-starter.md) | A Spring Boot starter that embeds the gateway | Accepted | Yes |
| [0022](0022-shadow-mode.md) | Shadow mode for guards | Accepted | Yes |
| [0023](0023-attribute-calls-to-a-user.md) | Attribute calls to a user | Accepted | Yes |
| [0024](0024-refuse-tools-by-name.md) | Refuse tools by name | Accepted | Yes |
| [0025](0025-console-as-a-static-app.md) | The console is a static app on the admin port | Accepted | Yes: the `tollgate-console` module, served at `/console/` with its security headers. Browser tests run in CI, and Dependabot and CodeQL cover npm |
| [0026](0026-console-sign-in.md) | Sign in to the console with the admin token | Accepted | Yes: the admin token, kept in the tab. The viewer token and `GET /admin/v1/session` come after 0.1.0 |
| [0027](0027-live-data-for-the-console.md) | Live data for the console comes from the audit trail | Accepted | Yes: `GET /admin/v1/live` and `/admin/v1/activity` |
| [0028](0028-audit-trail-through-the-admin-api.md) | Read and prove the audit trail through the admin API | Accepted | Yes: the audit routes and inclusion proofs. `audit prove` comes after |
| [0029](0029-config-in-the-console.md) | The console shows the config, and changes it only by rewriting the file | Accepted, amended | Yes: the config view and four edits (a guard's mode, a tenant's budget, create a key, revoke a key) when `TOLLGATE_ADMIN_EDITS=file` |
| [0030](0030-pause-a-tenant.md) | Pause a tenant | Accepted | No; it is built after 0.1.0 |
| [0031](0031-client-libraries-wrap-the-official-sdks.md) | Client libraries that wrap the official SDKs | Accepted | Yes |
| [0032](0032-say-the-error-code-in-a-header.md) | Say the error code in a header, and list Anthropic models | Accepted, amends [ADR 0005](0005-forward-requests-in-their-own-format.md) | Yes |
| [0033](0033-publish-the-client-libraries.md) | Build, test and publish the client libraries from this repo | Accepted, amends [ADR 0001](0001-runtime-and-build.md) | Yes |
| [0034](0034-in-process-java-mode.md) | An in-process mode for Java, after 0.1.0 | Accepted | No; decided after the 0.1.1 performance report |
