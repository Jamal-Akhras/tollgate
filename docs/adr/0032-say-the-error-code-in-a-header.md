# ADR 0032: Say the error code in a header, and list Anthropic models

Date: 2026-11-17 · Status: Accepted, amends
[ADR 0005](0005-forward-requests-in-their-own-format.md)

## Context

Errors come back in the format of the endpoint that was called (ADR 0005).
The two formats don't hold the same things:

- **The OpenAI body has a `code` field,** and Tollgate puts its own code
  there: `policy_denied`, `budget_exceeded`, `rate_limited`.
- **The Anthropic body has only a `type` from a fixed list.** Tollgate maps
  the status to it and the code is lost. A spent budget, a tenant's rate
  limit and a provider's rate limit are all `429 rate_limit_error`. They
  differ only in the wording of the message.

A program can't act on wording. A client library (ADR 0031) can't either, and
neither can a dashboard or a retry rule in someone's own code.

The model list has the same gap. `GET /v1/models` lists the tenant's models
on OpenAI-format providers only. Nothing lists the Anthropic-format ones, and
the Anthropic SDKs' `models.list()` gets a `404`.

## Decision

### `X-Tollgate-Error`

- **Every error the gateway writes carries `X-Tollgate-Error: <code>`,** on
  both endpoints and on `/v1/models`. The code is the one the OpenAI body
  already shows, such as `policy_denied` or `provider_timeout`. An
  unexpected failure carries `internal_error`.
- **The bodies don't change.** Each stays in its endpoint's format.
- **A response that isn't an error doesn't carry it.** Nor does a `404` for
  a path Tollgate doesn't serve, such as `/v1/embeddings` today: that answer
  is the web framework's, not the gateway's.
- **An error inside a stream has no header,** since the headers have gone.
  It stays an event, as today.
- **The codes are part of the contract from 0.1.0.** A new code may be added
  in a minor version. A code is not renamed or reused.
- **A spent budget also carries `x-should-retry: false`.** The official SDKs
  obey that header, and without it they send a refused call three times.
  Other errors don't carry it, and a rate limit keeps `Retry-After`.

### `GET /anthropic/v1/models`

- **It lists the calling tenant's models on Anthropic-format providers,** as
  `/v1/models` does for the OpenAI format.
- **The answer is in Anthropic's list shape:**

  ```json
  {"data":[{"type":"model","id":"claude-sonnet","display_name":"claude-sonnet",
            "created_at":"1970-01-01T00:00:00Z"}],
   "has_more":false,"first_id":"claude-sonnet","last_id":"claude-sonnet"}
  ```

  `first_id` and `last_id` are `null` when the list is empty.
- **It takes the key as the other routes do,** and answers `401` in the
  Anthropic error format without one.
- **The names are Tollgate's,** the ones a tenant asks for, not the
  provider's.
- **Paging parameters are ignored.** The whole list is one page.

## Alternatives considered

- **A `code` field in the Anthropic error body.** No header to learn, but the
  body is the provider's format, and a client that checks it strictly may
  reject a field it doesn't know.
- **Parsing the message in the client libraries.** It needs no server change
  and breaks the first time a message is reworded.
- **A Tollgate error format on both endpoints.** Clean, and it breaks every
  existing error handler, which ADR 0005 exists to prevent.
- **Reusing the SDKs' request ID header** to carry the code. It is for IDs.
- **Leaving `x-should-retry: false` off `budget_exceeded`.** A budget can
  free up within a second, when a call in flight settles for less than it
  reserved, so a retry is not always wasted. But the SDKs retry blindly, and
  a refused call sent three times is the usual case.
- **One list of every model on `/v1/models`,** whatever its format. An OpenAI
  client would then be offered models it can't call there.
- **Leaving the Anthropic list out of 0.1.0.** It is the smaller half of this
  ADR and the first thing to cut. The header is not: the libraries of
  ADR 0031 need it.

## Consequences

- **A client can act on the reason for a refusal** on either endpoint,
  without reading English.
- **The SDKs stop at a spent budget** and raise the error at once, instead of
  sending the call three times.
- **The codes become something Tollgate has to keep.** They already were, on
  the OpenAI endpoint.
- **A caller with a bad key learns nothing new:** the code is already in the
  OpenAI body, and `401` already says as much on the Anthropic one.
- **One more gateway route,** under `/anthropic/*`, which the starter already
  claims (ADR 0021).
- **A tenant can list everything it may call,** with two requests.
