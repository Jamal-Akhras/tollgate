# ADR 0011: Report each request's cost to tenants that ask

Date: 2026-11-03 · Status: Accepted

## Context

Tollgate already prices every call twice:

- **Before the call:** the most it can cost. This sizes the budget
  reservation ([ADR 0006](0006-budget-reservations.md)).
- **After the call:** what it cost, from the usage the provider reports. This
  is written to the audit trail and settles the budget.

Neither number reaches the application. A developer choosing between models,
tuning prompts or checking a budget has to read the audit trail. Prices now
depend on the call ([ADR 0009](0009-price-catalog.md)): long-context tiers,
reasoning rates and web searches. That makes guessing harder, and makes a
quick estimate more useful.

Costs are not secret from the operator, but they can be from a tenant. A
tenant that sees costs learns the prices and its own spend per call. So
reporting has to be a choice per tenant, and off by default.

## Decision

### Tenants opt in

A tenant with `showCosts: true` gets cost information. Every other tenant
sees no change.

### Cost headers on every response

- **`X-Tollgate-Cost-Max`:** the most the call could cost, worked out before
  it was sent. It appears only when the output is capped, by `max_tokens` or
  the model's `maxOutputTokens`; without a cap there is no maximum.
- **`X-Tollgate-Cost`:** what the call was charged, the same amount the audit
  trail records. When the provider reports no usage, that is the maximum.
- **Values** are written as Tollgate writes money everywhere, for example
  `0.0412 USD`.
- **Streaming:** headers are sent before the answer, so a stream carries only
  `X-Tollgate-Cost-Max`. The charged cost comes last, as an SSE comment line,
  `: tollgate-cost 0.0037 USD`, which clients ignore.
- **Refused calls** cost nothing and carry no cost headers.

### A dry run returns the estimate without calling the model

- **How:** a request with `X-Tollgate-Dry-Run: true`, from a tenant with
  `showCosts`.
- **What runs:** everything up to the provider call: authentication, routing,
  every guard, and the budget check, including shrink-to-fit. A dry run that
  a real call would refuse gets the same refusal.
- **What does not:** nothing is reserved or charged, and the provider is
  never contacted.
- **The answer** is `200` with a small JSON body in Tollgate's own shape, not
  the endpoint's: the model, the estimated input tokens, the output tokens
  allowed, the maximum cost, and where the price came from (the config, or the
  catalog and its version). The cost headers are set as well.
- **A dry run from a tenant without `showCosts`** is refused with `403`,
  rather than silently sent to the model.

### The audit trail records dry runs

A dry run writes an intent and an outcome, like any call. The outcome has the
new result `dry_run`, no status from a provider, and a cost of zero. Budget
replay therefore never charges it, and the trail shows every estimate that
was asked for. Older trails still verify; the record format does not change.

## Alternatives considered

- **Costs for every tenant.** Simplest, but every client learns the prices and
  spend, which the operator may not want.
- **Costs only in the audit trail and logs.** No new surface, but nobody
  building against Tollgate sees them while they work.
- **A separate estimate endpoint.** It would need its own request format and
  could drift from the real path. A header on the real endpoints runs the
  exact same checks.
- **Costs in the response body.** It would change the provider's format, which
  [ADR 0005](0005-forward-requests-in-their-own-format.md) keeps intact, and
  break strict clients.

## Consequences

- **Developers see what each call costs**, and can compare models with dry
  runs, without spending anything.
- **The headers repeat what the audit trail holds**, so the two always agree.
- **A dry run is still an estimate.** The real cost depends on the tokens the
  model uses, which is only known afterwards.
- **One more audit result**, `dry_run`, which tools reading the trail must
  accept.
