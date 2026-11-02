# ADR 0010: Relay streamed responses as they arrive

Date: 2026-11-02 · Status: Accepted

## Context

Tollgate refuses `"stream": true`. Most chat interfaces, coding agents and
SDK examples stream, so for many users the gateway does not work at all. Some
models need streaming for features such as Qwen's thinking mode.

A stream changes when Tollgate learns things:

- **Status:** by the time the provider's first event arrives, the response
  status is already `200`, so a later failure cannot change it.
- **Usage:** it comes only at the end. OpenAI sends it only if asked, in an
  extra final chunk. Anthropic spreads it across `message_start` and
  `message_delta`.
- **Clients can leave mid-answer**, and the provider keeps generating, and
  billing, unless told to stop.
- **The limits in [ADR 0008](0008-harden-the-request-path.md)** were written
  for one response body: the size cap, the deadline, the rebuilt responses
  and the model name.

Redacting output on a stream needs a hold-back window. That stays in M2 and is
not part of this decision.

## Decision

### What does not change

Authentication, routing, the request allowlist, guards, the budget
reservation and the audit intent all happen before the provider is called,
exactly as for other calls. A stream that is refused is refused with an
ordinary error response.

### Relaying

- **`stream` may be `true`.** For OpenAI, `stream_options` joins the
  allowlist.
- **Each event is relayed as soon as it arrives**, so the time to the first
  token is the provider's, not Tollgate's. Events are read line by line from
  the provider and written and flushed to the client one at a time, on the
  request's virtual thread. Nothing is buffered beyond the current event.
- **The `model` in every event is your model's name:**
  - every OpenAI chunk
  - Anthropic's `message_start`

  Other fields, `ping` events and OpenAI's `[DONE]` pass through unchanged.
- **The response is always `text/event-stream`**, with
  `X-Content-Type-Options: nosniff`, and with `X-Tollgate-Output-Limited`
  when the answer was shortened to fit a budget.
- **A provider error before the first event**, a non-`2xx` status, is
  rebuilt as an ordinary error response, as for non-streamed calls.

### Usage and budgets

- **OpenAI:** Tollgate always sends `stream_options.include_usage: true`.
  - If the client did not ask for usage, the final usage chunk is read for
    settlement and **not relayed**, because some clients assume every chunk
    has a choice.
  - If the client asked for it, the chunk is relayed with the rest.
- **Anthropic:**
  - **Input:** from `message_start`, unless a later `message_delta` reports a
    larger value.
  - **Output:** from the last `message_delta`.
  - **Web searches:** from the usage's `server_tool_use`.
- **Settlement happens when the stream ends**, from that usage. A stream that
  ends without usage is charged its full reservation, as in
  [ADR 0006](0006-budget-reservations.md).

### Limits

- **A gap limit:** a provider's `timeout` becomes the most time Tollgate waits
  for the first event and between any two events.
- **A length limit:** a stream may last at most
  `tollgate.max-stream-duration`, 10 minutes by default. A stream may also
  carry at most `tollgate.max-response-size` bytes in total.
- **A broken limit, or a malformed event,** ends the stream.
  - **The client** gets one final error event in the endpoint's own format:
    OpenAI's `data: {"error": …}`, or Anthropic's `event: error`. The
    connection is then closed.
  - **The provider connection** is cancelled.
  - **Billing:** the call is charged its full reservation.

### When the client leaves

When writing to the client fails because the client has gone, Tollgate:

- cancels the provider connection, so generation and billing stop sooner
- charges the full reservation, because the tokens already generated are not
  known
- records the outcome as `client_closed`

### The audit trail

- **New outcome results:** `interrupted`, for a limit or a malformed event,
  and `client_closed`. Both are added to
  [ADR 0007](0007-audit-trail.md)'s list.
- **The response HMAC** covers the bytes relayed to the client, in order. It
  is computed as the stream passes, so nothing is kept in memory.

## Alternatives considered

- **Buffering the whole stream and sending it at the end.** This is simple,
  and every non-streaming rule would apply unchanged. But it removes the only
  reason clients stream, and some clients time out waiting.
- **Passing the provider's bytes through untouched.** This is the fastest,
  but upstream model names would leak, and usage and the limits could not be
  enforced.
- **Estimating the tokens of an abandoned stream** from the text relayed so
  far. That would charge less, but a tokenizer per model is a large
  dependency. The full reservation is the safe upper bound.
- **Converting OpenAI streams into Anthropic streams, and back.** That is
  ruled out by ADR 0005.

## Consequences

- **Chat interfaces, coding agents and SDK streaming examples work through
  Tollgate,** with the same policies, budgets and audit.
- **An abandoned stream costs the tenant its full reservation,** which may be
  more than the provider bills. Budgets stay safe, at the cost of being
  pessimistic.
- **Long answers stop at the stream-duration limit.** Raise it for workloads
  that need longer streams.
- **Output redaction cannot apply to streams** until M2's hold-back window.
  Until then, a policy that redacts output must refuse to stream. The
  redaction ADR settles this.
