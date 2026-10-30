# ADR 0006: Reserve the most a call can cost, settle what it did cost

Date: 2026-10-29 · Status: Accepted

## Context

A budget is only a limit if it is checked before the money is spent. The cost of
a model call is known only afterwards, from the token counts in the provider's
response, and many calls for the same tenant run at once.

## Decision

- **Every model has a price,** with a rate per million tokens for input,
  output, cache reads and cache writes. Prices are required configuration;
  there is no built-in price list to go out of date.
- **Before the call, reserve an upper bound:**
  - **Input:** the forwarded request's size in bytes, plus 1,024 tokens for
    prompt text the provider adds itself (for example when tools are used),
    priced at the dearest of the three input rates. A token is never shorter
    than a byte, so this bounds the text.
  - **Output:** the model's `max_tokens`, which Tollgate sets, or lowers to the
    model's cap, in the forwarded request.
  - **The reservation:** it succeeds only if spend plus open reservations plus
    this amount stays within the limit. Otherwise the call is refused with a
    `429` before any provider is contacted.
- **After the call, settle:**
  - **Success with token counts:** charge the cost of the reported tokens.
  - **Success without token counts:** charge the full reservation.
  - **Provider error or unreachable provider:** charge nothing, since providers
    do not bill for these.
  - **Timeout:** charge the full reservation, because the provider may still
    have done the work.

  Settlement always runs, whatever the outcome.
- **Periods are calendar days or months in UTC, or the total.** A call is
  charged to the period in which it settles.
- **The `BudgetStore` interface** holds the reservations. The in-memory store
  ships now; Postgres comes with the production storage tier.

## Consequences

- Concurrent calls cannot spend past a limit together, because each one holds
  its worst case until it settles.
- Reservations are deliberately pessimistic, so a tenant close to its limit is
  refused slightly earlier than its actual spend would require.
- The bound holds for text. Images or files referenced by URL can cost more
  input tokens than their bytes suggest, so the settled cost of such a call can
  exceed its reservation. The overrun is charged, never ignored.
- With the in-memory store, spend resets when the server restarts.
