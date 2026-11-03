# ADR 0008: Harden the request path

Date: 2026-11-02 · Status: Accepted, amended by [ADR 0010](0010-streaming.md)

## Context

Tollgate exists so that no model call leaves without a policy decision, a
budget check and an audit record. A red-team review on 2026-11-02 attacked a
running gateway with a hostile tenant and a hostile provider. It found ways
around each of those promises:

- **Data can leave beyond the policy.** Requests are forwarded with every field
  and the `anthropic-beta` header intact. That lets a tenant tell the provider
  to:
  - connect to a third-party MCP server (`mcp_servers`)
  - fetch arbitrary URLs (`web_fetch`)
  - search the web
  - keep the conversation stored (`store`)

  The data-class guard only checks which provider receives a request, not
  where the provider sends it next.
- **The data-class check is optional.** A tenant whose policy has no
  `data-class` guard, or that falls back to a missing `default` policy, can
  send confidential data to any region.
- **Budgets undercount and forget.**
  - The reservation assumes one answer per call, but OpenAI's `n` returns up
    to 128. Thirty concurrent calls spent 38 times a tenant's limit.
  - Inputs passed by URL or file ID, server-tool fees and other pricing modes
    are not bounded at all.
  - Spend lives in memory, so a restart resets every budget.
- **One provider response can stop the gateway.**
  - **No size limit:** a 1.5 GB response body exhausted the heap and left the
    process running but answering on no port.
  - **No body timeout:** the provider timeout covers only the response headers,
    so a body sent one byte a second held a call for 51 seconds against a
    5-second timeout.
- **Provider responses reach tenants unfiltered.** Error bodies can carry the
  operator's account details, and a provider chooses the response's content
  type.
- **The perimeter leaks:**
  - The admin OpenAPI document is reachable on the public port as
    `/v3/api-docs.yaml`.
  - The admin API has no authentication, so any web page in an operator's
    browser can call it.
  - The gateway port listens on every interface in plain HTTP.
  - Audit files are readable by every local user.
  - Nothing updates the pinned base image, so the image's Java runtime would
    never receive patches.

## Decision

### Requests may ask only for what Tollgate understands

- **Request fields are allowed by name; anything else is refused.** Each
  endpoint has an allowlist of top-level fields. A request with any other field
  is refused with a `403 policy_denied` that names the field, and the refusal
  is audited like any other guard decision.
  - **OpenAI chat completions:**
    - `model`, `messages`, `max_tokens`, `max_completion_tokens`, `n`
    - `temperature`, `top_p`, `stop`, `seed`
    - `presence_penalty`, `frequency_penalty`, `logit_bias`, `logprobs`,
      `top_logprobs`
    - `tools`, `tool_choice`, `parallel_tool_calls`, `response_format`
    - `reasoning_effort`, `verbosity`, `user`, `safety_identifier`,
      `prompt_cache_key`
    - `store`, only when it is `false`
  - **Anthropic messages:**
    - `model`, `messages`, `system`, `max_tokens`
    - `temperature`, `top_p`, `top_k`, `stop_sequences`
    - `tools`, `tool_choice`, `thinking`, `metadata`
  - **Both endpoints** also accept `stream`, but only as `false` until
    streaming ships.
- **Only client-side tools.** A tool must be one the client runs itself:
  - **OpenAI:** a `function` or `custom` tool.
  - **Anthropic:** a tool with no `type`, or one of the types the client
    executes. Those are `custom` and the tool versions starting `bash_`,
    `text_editor_`, `computer_` and `memory_`.

  Server tools are refused, because the provider would run them on its own
  infrastructure, outside Tollgate's view. Examples are web search, web fetch
  and code execution. Unknown tool types count as server tools.
- **Content is inline unless a feature allows otherwise.** Each message
  content part is checked by type:
  - **Text** is allowed.
  - **Images** are allowed as inline data: base64 or a `data:` URL.
  - **Documents such as PDFs** are allowed as inline data: an Anthropic
    `document` block with a base64 or text source, or an OpenAI `file` part
    with `file_data`.
  - **Tool calls, tool results and thinking blocks** are allowed, and the same
    rules apply to their nested content.
  - **A remote URL or a provider file ID** is refused unless a feature below
    allows it, because it makes the provider fetch content Tollgate never
    sees.
  - **Any other part type** is refused, including audio, server-tool results
    and container uploads.
- **`anthropic-beta` is refused** unless the `unreviewed-fields` feature below
  allows it. Betas switch on provider features that this allowlist has not
  reviewed. Prompt caching needs no beta header.

### A policy can allow a reviewed feature for named data classes

A policy may list features, each with the data classes it is allowed for:

```yaml
policies:
  default:
    guards: [...]
    features:
      remote-images: [public, internal]
      web-search: [public]
```

A request that uses a feature its data class is not allowed is refused with a
`403 policy_denied` that names the feature. Five features exist:

- **`remote-images`:** image parts given as an `http` or `https` URL.
- **`remote-documents`:** Anthropic document blocks with a `url` source.
- **`provider-files`:** image, document and file parts that refer to a file ID
  held by the provider.
- **`web-search`:** Anthropic's `web_search` server tool.
  - **Search count:** Tollgate sets the tool's `max_uses`, lowering any larger
    value, to the model's `maxWebSearches`.
  - **Price:** the model must define `price.webSearch` per 1,000 searches.
  - **Billing:** the reservation includes `maxWebSearches` searches, and
    settlement charges the searches the provider reports.
- **`unreviewed-fields`:** top-level fields and `anthropic-beta` values that
  are not on the allowlist.
  - **Why it exists:** for clients whose fields change with every release.
    Claude Code is the main example: its gateway documentation says the beta
    headers and body fields it sends are open-ended and change between
    versions.
  - **What still applies:** the structural checks. Server tools are found by
    their `type` and refused unless a feature allows them. Remote URLs and
    file IDs are still refused.
  - **What is still refused:**
    - the fields listed under extra fields below
    - betas that switch on refused abilities or other pricing: those starting
      `mcp-client-`, `code-execution-`, `web-fetch-`, `files-api-` and
      `context-1m-`
  - **The risk accepted:** a new provider feature that sends data elsewhere
    through a new top-level field, as `mcp_servers` did, would pass for the
    listed data classes until it is added to the refused list. For that
    reason it should be allowed for `internal` data at most, never for
    `confidential`.

No other server tool can be allowed. Web fetch, the MCP connector and code
execution each send data to destinations that a policy cannot review in
advance: arbitrary URLs, third-party servers and remote sandboxes. OpenAI's
`web_search_options` stays refused until its pricing is modelled.

### A provider can accept extra fields

A provider may list `extraFields`: top-level request fields it accepts beyond
the endpoint's allowlist. This is for OpenAI-compatible servers with their own
settings, for example `top_k`, `min_p`, `num_ctx` or `chat_template_kwargs` on
Ollama or vLLM.

A config that lists a field this ADR refuses for where it sends data or how it
is billed is rejected, whatever the provider. `unreviewed-fields` never
allows these fields either. They are:

- `mcp_servers`, `container`
- `store`, `metadata`
- `web_search_options`
- `service_tier`, and Anthropic's `speed`, added on 2026-11-03 when fast
  mode stopped needing a beta header
- `modalities`, `audio`

### The data-class check cannot be left out

When `dataClasses` are defined, the config is rejected at load time if any
tenant's effective policy lacks a `data-class` guard. That includes a tenant
with no policy when no `default` policy exists. The error names the tenant. A
missing line now fails loudly instead of switching the check off.

### Budgets count every answer and survive restarts

These amend [ADR 0006](0006-budget-reservations.md).

- **`n` multiplies the output reservation.** `n` must be a whole number from 1
  to 128, and the reservation covers `n` times the output cap.
- **Near the limit, the answer shrinks instead of the call being refused.**
  - **When:** the worst case of a call does not fit in what is left of a
    tenant's budget.
  - **What happens:** Tollgate lowers the output cap (`max_tokens`, or
    `max_completion_tokens`) to the largest value whose worst case fits, and
    sends the call. The response carries `X-Tollgate-Output-Limited` with the
    lowered cap, so the application knows its answer may be cut short.
  - **The floor:** the cap is never lowered below the model's
    `maxOutputTokens.floor`, 256 tokens by default. A call that cannot fit
    even at the floor is refused with `429`, as before. The floor keeps
    structured output from being cut off into something unusable.
  - **The guarantee is unchanged:** spend plus open reservations never
    exceeds the limit.
- **Inline images get an input allowance.** Each one adds 5,000 tokens to the
  input bound.
  - **Anthropic:** its documented maximum is 4,784 tokens per image.
  - **OpenAI:** high detail stays near 4,000 tokens per image.
  - **OpenAI's `original` detail** can cost more, and that overrun is charged
    like the others below.
- **Documents and allowed features can still overrun.**
  - **Inline PDFs:** a page costs its text plus an image of the page, so a
    dense PDF can cost more than its size suggests.
  - **Remote and file inputs:** where a policy allows them, their cost is not
    known before the call.

  Such a call can settle above its reservation. The overrun is charged, as
  ADR 0006 already provides, and is visible in the audit record.
- **Spend is rebuilt from the audit trail at startup.** When the audit is on,
  Tollgate replays the current period from the trail before serving:
  - It adds each outcome's settled cost to its tenant's spend.
  - It charges the full reservation for each allowed intent that has no
    outcome, because the provider may have done the work before the crash.

  When the audit is off and a tenant has a budget, startup logs a warning that
  spend resets on restart.

### Provider responses are bounded and rebuilt

- **A size limit.** `tollgate.max-response-size`, 32 MB by default, caps a
  response body. A larger body is cut off and reported as a `502
  provider_invalid_response`.
- **A deadline for the whole response.** A provider's `timeout` covers the
  whole exchange, the body included. The request is sent asynchronously and
  cancelled when the deadline passes.
- **Successful bodies must be JSON objects.** Anything else is a `502
  provider_invalid_response`. In both cases the full reservation is charged,
  because the provider may have done the work.
- **Tollgate owns the response headers.** Responses are always
  `application/json` with `X-Content-Type-Options: nosniff`. `Retry-After` is
  the only provider header passed on.
- **Errors are rebuilt in the endpoint's format.** For `400`, `404`, `413` and
  `422`, the provider's message is kept, because the client needs it to fix
  its request. Every other status gets Tollgate's own message. The original
  is logged with the trace ID.
- **`model` is rewritten to the name the client used.** Clients never learn
  upstream model IDs. This narrows the pass-through rule in
  [ADR 0005](0005-forward-requests-in-their-own-format.md) by one field in
  each direction.
- **`max_tokens` is filled in only when a budget needs it.**
  - **Without a budget:** a request that sends no output cap reaches the
    provider without one, so the provider's own default applies. A cap above
    the model's `maxOutputTokens.cap` is still lowered.
  - **Why:** filling in a default for every request cut answers short. On
    thinking models it was worse: reasoning and answer share the cap, and one
    model server ended the model's reasoning after a single token.
  - **Thinking models with a budget** need a generous `default`.

### The perimeter

- **The admin API needs a token.** Requests must carry `Authorization:
  Bearer` with the value of `TOLLGATE_ADMIN_TOKEN`, which must be at least 32
  characters, and the token is compared in constant time.
  - Without the variable, admin endpoints answer `503 admin_disabled`.
  - A browser cannot attach the header to a cross-site request without a CORS
    preflight, and Tollgate answers no preflights. This also covers forged
    requests and DNS rebinding.
  - The OpenAPI document on the admin port stays open, since it contains no
    secrets.
- **Admin paths are blocked by prefix.** On the public port, every path that
  starts with `/v3/api-docs` returns `404`, not only `/v3/api-docs` and the
  paths below it.
- **Loopback by default.**
  - The gateway port binds to `127.0.0.1` unless `TOLLGATE_ADDRESS` says
    otherwise.
  - The image sets `0.0.0.0`, because container ports are published
    explicitly. The README publishes `127.0.0.1:8080:8080`.
  - Clients on other machines must connect through a TLS-terminating proxy.
- **Audit files are private.** On POSIX systems the audit directory is `0700`
  and its files `0600`.
- **The image cannot outlive a heap failure.** The README's `java -jar`
  command adds `-XX:+ExitOnOutOfMemoryError`, as the image already does.
- **The base image stays patched.** A weekly workflow resolves the current
  digest of `gcr.io/distroless/java25-debian13:nonroot`. It opens a pull
  request when the digest pinned in `pom.xml` is out of date, so CI tests the
  update before it is merged.

## Alternatives considered

- **A denylist of known dangerous fields.** It would be shorter and break
  fewer requests, but every new provider feature would pass until someone
  noticed. A governance gateway has to fail closed.
- **No feature switches until someone asks.** That means less config, but a
  team that needs web search on public data, or images by URL, could not use
  Tollgate at all. Five switches, each tied to data classes, keep the strict
  default without leaving teams stuck.
- **Allowing every server tool behind a switch.** Web fetch, the MCP
  connector and code execution cannot be made safe by data class, because the
  risk is where the data goes after the provider receives it.
- **Refusing every call whose worst case does not fit.** This is the original
  rule. It also never overspends, but it refuses calls near the limit that
  would almost certainly have cost far less. Shrinking the answer keeps the
  same guarantee and serves the call.
- **An overdraft above the limit.** Checking the worst case against the limit
  plus an allowance would bound the overspend, not prevent it. It can be
  added as a setting if a team prefers it to shorter answers.
- **Reserving a typical cost, not the worst.** Under concurrent calls this is
  not a cap at all. The red-team review spent 38 times a limit this way, and
  other gateways have shipped the same failure.
- **Dropping disallowed fields silently.** The call would succeed but behave
  differently from what the client asked for, with nothing to say why.
  Refusing names the field.
- **Making the data-class check built in, not a guard.** That would protect
  every tenant without config, but it would hide the check from the policy
  and its audited guard steps. Rejecting the config keeps it visible.
- **Persisting budgets in their own file.** That would be a second record of
  spend that could disagree with the audit trail, which already holds every
  settled cost.
- **Mutual TLS for the admin API.** It is stronger, but it means certificates
  for every operator. The token can be swapped for it later without changing
  the API.

## Consequences

- **Some valid requests are refused until their fields are reviewed**, for
  example a field OpenAI adds next month. The refusal names the field, and
  adding it to the allowlist is a one-line change with a test.
- **Model abilities are unchanged; some API features need a policy.**
  - **Unaffected:** chat, client-side tool calling, structured output,
    thinking, prompt caching, and inline images and PDFs.
  - **Need a policy feature:** images and documents by URL, provider file IDs,
    Anthropic web search, and the changing betas and fields of clients such
    as Claude Code.
  - **Refused outright:** web fetch, the MCP connector, code execution, and
    audio.
- **Verdict's calls stay allowed:** a forced Anthropic tool call, OpenAI JSON
  schema output, and an OpenAI-compatible local model.
- **Budgets hold under concurrency and across restarts**, as long as the audit
  is on. Overruns are still charged when they happen, but the inputs that
  caused them are now refused.
- **Memory has a known bound:** concurrent calls times the sum of the
  request and response limits. Per-tenant concurrency and rate limits remain
  M2 work.
- **The admin token changes the reload command.** The README, the `keys
  create` output and the OpenAPI document all show the `Authorization`
  header.
