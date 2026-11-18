# tollgate-client

OpenAI and Anthropic clients for Python, already pointed at a Tollgate gateway.

```
pip install "tollgate-client[openai]"
pip install "tollgate-client[anthropic]"
```

Install the extra for the SDK you use. Then import it as `tollgate`:

```python
import tollgate

client = tollgate.openai()
reply = client.chat.completions.create(model="qwen3", messages=[{"role": "user", "content": "Hi"}])

claude = tollgate.anthropic(data_class="confidential", user="u-17")
raw = claude.messages.with_raw_response.create(
    model="claude-sonnet", max_tokens=1024, messages=[{"role": "user", "content": "Hi"}]
)
print(tollgate.receipt(raw).cost, raw.parse().content)
```

The address comes from the argument, then `TOLLGATE_BASE_URL`, then `http://localhost:8080`.
The key comes from the argument, then `TOLLGATE_API_KEY`.

Source and documentation: https://github.com/Jamal-Akhras/tollgate
