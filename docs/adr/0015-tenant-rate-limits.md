# ADR 0015: Limit each tenant's request rate, token rate and concurrent calls

Date: 2026-11-09 · Status: Accepted

## Context

A budget caps what a tenant spends over a day or a month. It does nothing
about how fast the tenant spends it:

- **One tenant can starve the others.** A loop in one application can send
  hundreds of calls a second. They all share the provider account's own rate
  limit, so every other tenant starts getting `429` from the provider.
- **Free models have no budget to stop them.** A local model priced `free`
  costs nothing, so a budget never refuses a call to it, but the GPU behind
  it still has a limit.
- **Memory has no per-tenant bound.** ADR 0008 bounded memory by concurrent
  calls times the request and response limits, and left the number of
  concurrent calls open.

ADR 0008 left per-tenant rate and concurrency limits to M2. This ADR covers
one Tollgate instance. Limits shared across instances need Redis, which comes
with the production storage tier.

## Decision

### Three optional limits per tenant

```yaml
tenants:
  support-bot:
    models: [claude-sonnet, qwen3]
    budget: { limit: 50.00, period: monthly }
    limits: { requestsPerMinute: 60, tokensPerMinute: 200000, concurrent: 8 }
```

- **Any of the three may be left out.** A tenant without `limits` is not
  limited, as today.
- **Each must be a positive whole number.** Zero is rejected when the config
  loads; to stop a tenant, remove its keys.
- **The limits cover the tenant as a whole,** across all its keys and models.

### How each limit is counted

- **Requests per minute** is a token bucket that holds up to the per-minute
  number and refills continuously. A tenant that has been quiet can send a
  burst of that size at once, then one request every `60 / n` seconds. This
  is how OpenAI and Anthropic count their own limits, so a client that
  behaves for them behaves here.
- **Tokens per minute** is a bucket of the same kind, charged after each call
  with the input and output tokens the provider reported, cached tokens
  included. A call is refused while the bucket is below zero. Calls already
  in flight can take it there, and the tenant then waits until it refills.
  A call the provider refused or that reported no usage charges nothing.
- **Concurrent** counts calls in flight, streams included. A call stops
  counting when its answer has been sent, the stream has ended, or the
  client has gone.

### Where they are checked

- **Requests and concurrent calls** are checked straight after the API key,
  before the body is read. A tenant over its limit can't make Tollgate parse,
  scan or redact anything. Every request that passes this check counts,
  including ones a guard refuses later, as at the providers.
- **Tokens** are checked at the same point: a call is refused while the
  bucket is below zero. Nothing is reserved in advance.
- **Dry runs** count as requests and concurrent calls, and charge no tokens.

### What the client sees

- **`429 rate_limited`,** in the error format of the endpoint that was called.
  The message names the limit and its value, for example "This tenant is
  limited to 60 requests a minute". OpenAI's and Anthropic's SDKs already
  retry a `429` with backoff.
- **`Retry-After`,** in whole seconds rounded up, says when the bucket will
  allow the next call. A concurrency refusal has none, because Tollgate can't
  know when a call in flight will end.
- **Over the limit is never queued.** Waiting would hold a thread and the
  request in memory, which is what the limit exists to prevent.

### What is recorded

- **These refusals are not audited.** They happen before the request is read,
  like a wrong API key, so there is nothing to fingerprint. Recording each one
  would also let a flood fill the audit disk.
- **A metric counts them instead,** `tollgate.limits.refused`, tagged with the
  tenant and the limit. A warning is logged at most once a minute per tenant
  and limit.

### Reloads and restarts

- **A reload keeps each tenant's state.** A bucket remembers how much of a
  minute it has used, so if a limit changes, the tenant keeps the same share
  of the new one. Calls already in flight finish. A tenant whose limits are
  removed stops being counted.
- **A restart starts every bucket full.** Unlike budgets, limits aren't
  rebuilt from the audit trail. At worst a restart gives a tenant one extra
  minute's allowance.
- **With several instances,** each enforces the limits on its own, so the
  effective limit is the instance count times the configured one. The README
  says so until Redis arrives.

## Alternatives considered

- **Reserving tokens before the call, as budgets do.** Budgets need a hard
  cap, so they reserve the worst case: the request's size in bytes as its
  input tokens, which is about four times the real count. A rate limit
  reserving that would refuse a 100 KB prompt under a 100,000-token limit,
  although it uses about 25,000 tokens. A rate limit smooths load, so going
  over by the calls already in flight is acceptable. The budget stays the
  hard cap on money.
- **Fixed one-minute windows.** Simpler, but a client can send twice the
  limit across the boundary between two windows.
- **Queueing calls over the limit.** Kinder to clients that don't retry, but
  it ties up memory under exactly the load the limit is for.
- **Limits per model or per key.** Useful, but nobody needs them yet. A per-
  provider concurrency limit, to stay under the provider account's own rate
  limit, belongs with provider fallback.
- **OpenAI's `x-ratelimit-*` headers on every answer.** Clients don't need them
  to back off, and `Retry-After` covers the refusals.

## Consequences

- **One tenant can no longer starve the others,** or a local GPU, on a single
  instance.
- **Memory is bounded per tenant** by its concurrent limit times the request
  and response limits.
- **The token limit can run over** by the calls in flight when it is reached.
- **Rate-limit refusals don't appear in the audit trail,** only in metrics and
  the log.
- **Limits are per instance** until the Redis tier.
