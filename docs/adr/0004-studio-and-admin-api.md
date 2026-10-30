# ADR 0004: An API-first admin surface with a server-rendered Studio

Date: 2026-10-29 · Status: Accepted

## Context

Operators need a UI to set safeguards without editing YAML, and some will want
to manage Tollgate from their own portals or automation instead. Security
reviewers in regulated organizations audit every dependency an admin
component ships.

## Decision

- **The admin API is the product.** It is described by an OpenAPI document
  that is published in `docs/api/admin-openapi.json`. A test fails when the
  code and that document disagree, so the contract changes only through a
  reviewed commit.
- **Studio is one client of that API,** rendered on the server with JTE
  (compiled, type-checked templates) and htmx. JavaScript dependencies are
  vendored at pinned versions with integrity hashes. There is no npm toolchain
  and no bundler.
- **A strict Content-Security-Policy:** no inline scripts, and htmx's
  `eval`-based features disabled.
- **The admin API, Studio and the OpenAPI document are served only on the admin
  port.**

## Alternatives considered

- **React single-page app.** Familiar and flexible, but it adds a Node
  toolchain and hundreds of transitive packages to the security review, for a
  UI that is mostly forms, tables and a simulator.
- **Angular.** Common in enterprises, but heavier and with the same toolchain
  cost.
- **Vaadin.** Java-only, but heavy and ties the UI to one vendor.

## Consequences

- One build (`mvn verify`) covers the whole product, and Java contributors can
  change the UI.
- Interactions are limited to what server rendering and htmx do well: forms,
  partial updates and server-sent events. Rich charts use small vendored
  libraries.
- Anyone who wants a different UI builds it against the published contract.
