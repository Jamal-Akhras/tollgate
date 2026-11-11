# ADR 0018: The OpenAI Responses API and embeddings

Date: 2026-11-10 · Status: Accepted

## Context

Tollgate serves OpenAI chat completions and Anthropic messages. Two OpenAI
endpoints are missing:

- **`/v1/responses`** is OpenAI's main API now. New SDK features, the Agents
  SDK and coding agents use it, and Ollama, vLLM and OpenRouter serve it too.
- **`/v1/embeddings`** is used by every retrieval system. Embedding calls carry
  documents and queries, so they need the same policy, redaction, budget and
  audit as chat.

The Responses API differs from chat completions in ways that matter for a
gateway:

- **The provider keeps state by default.** `store` defaults to `true`, so
  OpenAI keeps every request and answer unless told otherwise.
  `previous_response_id`, `conversation`, `prompt` and `background` all rely on
  content stored at the provider, which Tollgate never sees.
- **All tenants share Tollgate's provider key.** If tenant A's response were
  stored, tenant B could name its ID in `previous_response_id` and get an
  answer built on A's conversation, or fetch it outright.
- **It has more server-side tools:** web search, file search, code
  interpreter, computer use, image generation and remote MCP servers.
- **It has its own shapes:** input items instead of messages,
  `max_output_tokens`, typed stream events ending in `response.completed`, and
  usage with cached and reasoning tokens as details.

## Decision

### Two new endpoints, in their own format

- **`POST /v1/responses` and `POST /v1/embeddings`,** for models whose provider
  uses the `openai` format. As with chat (ADR 0005), Tollgate forwards the
  request in its own format and changes only the model and the output cap.
  A provider that doesn't serve the endpoint answers for itself, usually
  `404`.
- **Embedding models are marked** with `kind: embedding`. They are served only
  on `/v1/embeddings`, and chat models are not. An embedding model needs no
  output cap and no output price.

```yaml
models:
  embed-small:
    provider: openai
    upstream: text-embedding-3-small
    kind: embedding
```

- **The audit's `endpoint` field** gains `openai-responses` and
  `openai-embeddings`, next to `openai` and `anthropic`. The record format
  doesn't change otherwise.

### Responses are stateless at the provider

- **`store` becomes `false`** when the request leaves it out, and an explicit
  `store: true` is refused, as it is for chat completions.
- **`previous_response_id`, `conversation`, `prompt` and `background` are
  refused.** Each one uses content stored at the provider, which no policy has
  checked and which another tenant could have created.
- **No `GET`, `DELETE` or `cancel` on `/v1/responses/{id}`.** With nothing
  stored there is nothing to fetch.
- **Clients send the whole conversation each time,** as Ollama's Responses API
  already requires. Reasoning carries over between turns through
  `include: ["reasoning.encrypted_content"]` and the encrypted reasoning items
  sent back in the next request.

Stored state could come later. It needs Tollgate to record which tenant owns
each response ID and to check that on every reference, which belongs with the
Postgres tier.

### The same checks as chat

- **Tools:** `function` and `custom` tools, which the client runs, are allowed.
  Tools the provider runs are refused, including when `unreviewed-fields` is
  allowed, as server tools are for chat. That includes the `mcp` tool;
  agents reach MCP servers through Tollgate's own endpoint instead
  ([ADR 0019](0019-mcp-through-tollgate.md)).
  Web search on this endpoint can be
  added later once its cost can be capped; the Responses API has no limit on
  the number of searches, unlike Anthropic's.
- **Content:** inline images and files are allowed. Images by URL need
  `remote-images`, files by URL need `remote-documents`, and file IDs need
  `provider-files`, as for chat. Refused fields such as `service_tier` stay
  refused.
- **Redaction** reads `instructions`, text input, and the output of function
  calls sent back. It restores values in output text, function call arguments
  and their stream deltas, using the hold-back from ADR 0012.
- **Budgets:** `max_output_tokens` is filled in for tenants with a budget and
  lowered near the limit, as `max_tokens` is for chat. Reasoning tokens count
  as output.
- **Usage:** cached input tokens are charged at the cache-read rate and
  reasoning tokens at the reasoning rate.
- **Streams** are relayed event by event, with your model's name in each
  `response` object. Usage comes from `response.completed`, so Tollgate
  doesn't need to ask for it.

### Embeddings

- **Input:** a string or a list of strings. A list of token IDs is refused
  when the tenant's policy redacts or refuses anything, because token IDs
  can't be checked.
- **Redaction applies.** Placeholders are embedded instead of the values, so
  a document and a query that both contain an email still share
  `[EMAIL_1]`. There is no answer to restore.
- **Budgets** reserve the input, sized by the request in bytes as for chat,
  and settle from the reported `prompt_tokens`.
- **The answer** keeps its vectors as they are; only `model` is rewritten.

### Metrics and traces

The endpoints use the metrics and spans of ADR 0017. Provider spans are named
`chat` or `embeddings` after the GenAI operation.

## Alternatives considered

- **Passing `store`, `previous_response_id` and the other stateful fields
  through.** Simplest, but it lets one tenant read another's conversations
  through the shared key, and the provider keeps content no policy checked.
- **Tracking response IDs per tenant in memory now.** It would forget them on
  restart and differ between instances. It belongs with shared storage.
- **Translating Responses requests into chat completions** for providers that
  lack the endpoint. ADR 0005 rejected translation, and Ollama and vLLM now
  serve the endpoint themselves.
- **Skipping redaction for embeddings** to keep vectors exact. It would send
  the very data a policy chose to keep back.
- **Inferring embedding models from their name.** Too fragile; the config
  says which models are which.

## Consequences

- **Clients on the Responses API can use Tollgate,** as long as they send the
  whole conversation. The Agents SDK and Codex can work that way; features
  that need stored state get a clear refusal.
- **Retrieval traffic goes through the same policy, budget and audit** as chat.
- **Embeddings of redacted text differ** from those of the original, which
  can lower retrieval quality for the redacted values.
- **Each endpoint is more request shape to review and test,** and the
  allowlist grows by the Responses API's fields and item types.
