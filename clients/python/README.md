# tollgate-client

OpenAI and Anthropic clients for Python, already pointed at a
[Tollgate](https://github.com/Jamal-Akhras/tollgate) gateway. Tollgate is a
policy and audit gateway for LLM traffic.

```
pip install "tollgate-client[openai]"
pip install "tollgate-client[anthropic]"
```

Install the extra for the SDK you use. Then import it as `tollgate`. Python
3.10 or later.

```python
import tollgate

client = tollgate.openai()
reply = client.chat.completions.create(model="qwen3", messages=[{"role": "user", "content": "Hi"}])
```

The address comes from the argument, then `TOLLGATE_BASE_URL`, then `http://localhost:8080`.
The key comes from the argument, then `TOLLGATE_API_KEY`. The library reads no
other variable, so `OPENAI_API_KEY` and `ANTHROPIC_API_KEY` never reach Tollgate.
`async_openai()` and `async_anthropic()` are the async clients.

## Headers

`data_class` and `user` on a client are sent with every call. The user goes
in `X-Tollgate-User`, and is recorded when the tenant's `userHeader` names
that header. For one call, `tollgate.headers()` builds the headers, and
`dry_run` asks for a cost estimate without calling the model. Costs and dry
runs are for tenants with `showCosts: true`:

```python
claude = tollgate.anthropic(data_class="confidential", user="u-17")
claude.messages.create(
    model="claude-sonnet",
    max_tokens=1024,
    messages=[{"role": "user", "content": "Hi"}],
    extra_headers=tollgate.headers(dry_run=True),
)
```

## Receipt

`tollgate.receipt(raw)` reads what Tollgate said about a call from a raw
response: `trace_id`, `cost`, `cost_max` and `output_limited`. The two costs
are `None` unless the tenant has `showCosts: true`.

```python
raw = claude.messages.with_raw_response.create(
    model="claude-sonnet", max_tokens=1024, messages=[{"role": "user", "content": "Hi"}]
)
print(tollgate.receipt(raw).trace_id, raw.parse().content)
```

## Refusal

`tollgate.refusal(error)` reads why Tollgate refused a call from the SDK's
error: `code`, `message`, `status`, `trace_id` and `retry_after`. It returns
`None` for an error that didn't come from Tollgate.

```python
try:
    client.chat.completions.create(model="qwen3", messages=[{"role": "user", "content": "Hi"}])
except Exception as error:
    why = tollgate.refusal(error)
    if why is not None:
        print(why.code, why.trace_id)
```

The library retries nothing, logs nothing and decides nothing: a call made
without it gets the same guards, budget and audit.

## More

Source, documentation and the gateway itself: https://github.com/Jamal-Akhras/tollgate

The client libraries are described in
https://github.com/Jamal-Akhras/tollgate/blob/main/docs/client-libraries.md

Licensed under the Apache License 2.0:
https://github.com/Jamal-Akhras/tollgate/blob/main/LICENSE
