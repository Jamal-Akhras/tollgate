# ADR 0016: Fall back to another model when a provider can't answer

Date: 2026-11-09 · Status: Accepted

## Context

Each model maps to one provider. When that provider is down, slow, over its
own rate limit or out of capacity, every call to the model fails:

- **An outage is passed straight on.** A `503` from Anthropic or a stopped
  Ollama becomes a `502` for every client, although another provider could
  have answered.
- **A dead provider costs a full timeout per call.** A provider that accepts
  connections but never answers holds each call for its `timeout`, two minutes
  by default. Calls pile up behind it, and with them their memory.
- **Nothing limits what one provider is sent.** A local GPU serves a few calls
  at a time. The rest wait in Tollgate until they time out. ADR 0015 left a
  per-provider concurrency limit to this ADR.

DESIGN step 7 plans a timeout, a per-provider concurrency limit, a circuit
breaker and fallback before the first byte reaches the client. Timeouts
exist. This ADR covers the other three.

## Decision

### A model can name its fallbacks

```yaml
providers:
  ollama:    { format: openai, baseUrl: http://gpu-box:11434/v1, region: local, concurrent: 2 }
  openrouter: { format: openai, baseUrl: https://openrouter.ai/api/v1, apiKey: ${env:OPENROUTER_KEY}, region: us }

models:
  qwen3:
    provider: ollama
    upstream: qwen3:32b
    price: free
    fallback: [qwen3-cloud]
  qwen3-cloud:
    provider: openrouter
    upstream: qwen/qwen3-32b
```

- **Fallbacks are other models,** tried in order. Each keeps its own provider,
  upstream name, price and output cap.
- **Only the requested model's list is used.** A fallback's own fallbacks are
  not followed, so there are no chains or cycles.
- **A fallback must use the same wire format** as the model it backs up,
  since Tollgate does not translate between formats (ADR 0005). The config is
  rejected otherwise.
- **A tenant does not need to list a fallback** to be served by it, and can't
  call it directly unless it does. A fallback must still meet what the
  tenant's settings require of its models: an output cap when the tenant has a
  budget, and a web search price when its policy allows web search. The config
  is rejected otherwise.

### Every attempt is checked like a request of its own

- **The tenant's policy runs again for each fallback,** with that model's
  provider and region. A fallback the policy refuses is skipped and its
  refusal is audited. Confidential data that must stay local never falls back
  to a cloud provider.
- **Personal data is scanned once.** What is redacted depends on the data
  class, not the provider, so every attempt sends the same redacted body.
- **Each attempt reserves its own budget,** sized by its model's price, and
  the attempt before it is settled first by the existing rules. A fallback
  that doesn't fit the budget is refused like any other call.
- **The tenant's limits count the request once.** Its tokens are charged from
  whichever attempt answered.

### When Tollgate moves on

It moves to the next model when the provider:

- can't be reached, or times out
- answers `401`, `403`, `408`, `429` or any `5xx`, Anthropic's `529` included
- is skipped by its circuit breaker or its concurrency limit, below

It does not move on for an answer about the request itself, such as `400`,
`404`, `413` or `422`, which another provider would refuse too, or for an
invalid response, which may already have been billed.

- **Streams fall back only before the first byte** is sent to the client,
  that is, when the provider can't be reached or answers with an error status.
  Once a stream has started, a failure ends it as today.
- **There are no retries on the same provider.** OpenAI's and Anthropic's
  SDKs already retry, and retrying inside Tollgate as well would multiply the
  attempts and hold more calls open.
- **The last failure is what the client sees,** in the same form as today.

### A circuit breaker for each provider

- **Five failures in a row open it.** A failure is a provider that can't be
  reached, times out or answers with a `5xx`.
- **While it is open,** calls skip the provider without waiting. With no
  fallback left, they get `503` `provider_unavailable` at once, with
  `Retry-After` set to when the breaker will next let a call through.
- **After 30 seconds** one call is let through. If it succeeds the breaker
  closes; if it fails, it stays open for another 30 seconds.
- **The breaker opens and closes in the log,** once each time.

The five failures and 30 seconds are fixed for now. They can become settings
when someone needs different ones.

### A concurrency limit for each provider

- **`concurrent` on a provider** caps the calls in flight to it, across all
  tenants, streams included.
- **A call over the limit** moves on to its next fallback. With none left it
  gets `503` `provider_busy` at once, without `Retry-After`, because Tollgate
  can't know when a call in flight will end. Nothing is queued, for the
  reasons in ADR 0015.
- **The slot is taken before the budget is reserved,** so a busy refusal
  touches nothing. Dry runs don't take one, since they call no provider.
- **A limit changed by a reload** applies to the calls already in flight.

### What the client and the operator see

- **The answer names the model that produced it,** so `model` in the body is
  `qwen3-cloud` when the fallback answered.
- **Each attempt is its own intent and outcome in the audit trail.** The first
  keeps the request's trace ID; later ones add `.2`, `.3` and so on. The
  record format, `audit verify` and the budget replay need no change.
- **Calls skipped by a breaker or a concurrency limit are not audited** when
  nothing was sent, like ADR 0015's refusals. A request whose every model was
  skipped this way leaves no audit record.
- **The metric `tollgate.fallbacks`,** tagged with the model, the provider it
  moved on from and the reason, counts each time Tollgate moves on.
- **The metric `tollgate.providers.busy`,** tagged with the provider, counts
  calls refused because the provider had no slot left, and a warning is
  logged at most once a minute per provider.

## Alternatives considered

- **Retries with backoff on the same provider.** They compound with the SDKs'
  own retries, and they hold the call and its memory while they wait. A
  fallback provider is the faster recovery.
- **Resilience4j for the breaker.** It does far more than this needs. The
  breaker here is a counter and a timestamp per provider, and its rules are
  tied to Tollgate's own notion of a failure.
- **Fallbacks per tenant or per policy.** More flexible, but every tenant of a
  model would then need its own list. The policy already decides which
  fallbacks a tenant may use, through data classes and regions.
- **Falling back across formats.** It would need request and response
  translation, which ADR 0005 decided against.
- **Falling back after a stream has started.** The client has already
  received part of one answer, and a second answer can't continue it.
- **Spreading load across providers by weight.** Useful at scale, but nobody
  needs it yet, and fallback with a concurrency limit already lets a busy
  local model spill over to another.

## Consequences

- **An outage of one provider no longer fails the calls** that another
  provider could serve, within what the tenant's policy allows.
- **A dead provider costs five timeouts,** not one per call, while the breaker
  is open.
- **A local model can be capped** at what its hardware serves, and spill the
  rest to a fallback or refuse it at once rather than queue it.
- **A request can cost more than once.** A provider that timed out may still
  bill its attempt, and its full reservation is charged, as today.
- **A request can wait for several timeouts** before an answer. Primary
  models with a fallback should have a shorter `timeout`.
- **The client may get an answer from a different model** than it asked for,
  and the body says which.
