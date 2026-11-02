# ADR 0005: Forward requests in their own format

Date: 2026-10-29 · Status: Accepted, amended by [ADR 0008](0008-harden-the-request-path.md)

## Context

Applications call models through the OpenAI SDK, the Anthropic SDK, or both.
A gateway can accept one format and translate it into each provider's, or
accept each format and forward it unchanged.

Translation is where gateways break. Structured output, tool use, images,
prompt caching and stop reasons work differently in each format, and every
provider release adds fields a translator has to learn about. A field the
translator does not know is dropped silently, which is the failure Tollgate
exists to prevent.

## Decision

- Tollgate serves each format on its own endpoint: `/v1/chat/completions` for
  OpenAI and OpenAI-compatible servers, `/anthropic/v1/messages` for Anthropic.
- A model is available only on the endpoint of its provider's format. Asking
  for it on the other endpoint is a `400` that names the right one.
- The request body is forwarded as received, except for two fields: `model` is
  replaced with the provider's own model name, and `max_tokens` is set or
  lowered to the model's cap so the budget reservation is a true upper bound.
- Guards read only the fields they need. Usage is read from each format's own
  response fields.
- Errors are returned in the format of the endpoint that was called.

## Alternatives considered

- **Translate everything to one format.** One endpoint for applications, but a
  large, permanently changing surface that loses features in transit.
- **Translate only common fields and reject the rest.** Safer, but it still
  stops applications from using provider features until Tollgate catches up.

## Consequences

- Applications keep their SDKs and change only the base URL; new provider
  features work through Tollgate on the day they ship.
- An application that wants to switch a model between formats has to change
  SDK. Translation can be added later as an opt-in, on top of this design.
- Unknown request fields reach the provider. Guards that must see a field, such
  as content redaction, read it explicitly and fail closed on content they
  cannot inspect.
