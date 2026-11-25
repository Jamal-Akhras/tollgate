# Documentation

Start with the [README](../README.md) for what Tollgate is and the quickstart.

## Using Tollgate

- [What works today](features.md): every feature in 0.1.0, with links to the decisions behind it.
- [Configuration](configuration.md): `tollgate.yaml`, the config `tollgate init` writes, reloads and edits.
- [Deploying](deploy.md): the Docker image and a systemd service.
- [Operating](operations.md): ports, who can connect, metrics, traces, the admin token, reloads, usage and budgets, audit modes.
- [The console](console.md): the web app on the admin port, signing in and its four edits.
- [The client libraries](client-libraries.md): `tollgate-client` for Python, TypeScript and Java.
- [Inside a Spring Boot application](spring-boot-starter.md): the starter.
- [Claude Code through Tollgate](clients/claude-code.md): pointing a coding agent at Tollgate.

## Reference

- [Design](DESIGN.md): purpose, modules, request lifecycle, audit, money, quality bar and roadmap.
- [Architecture decision records](adr/README.md): one record for each decision with lasting consequences, and the index of what is built.
- [Admin API contract](api/admin-openapi.json): the OpenAPI 3.1 document of the admin API. It is generated from the code, checked in CI, and served by a running server at `/v3/api-docs/admin` on the admin port.
- [Grafana dashboard](grafana/tollgate.json): calls, refusals, spend, budgets, tokens, provider latency, fallbacks and calls in flight, from Tollgate's Prometheus metrics. In Grafana, go to Dashboards, then New, then Import, upload the file and pick your Prometheus data source. The tenant, model and provider filters fill in from the metrics.

## The project

- [Contributing](../CONTRIBUTING.md)
- [Security policy](../SECURITY.md)
- [Changelog](../CHANGELOG.md)
