# The client libraries

The libraries build the official OpenAI or Anthropic client for you, with the
base URL, the tenant key and Tollgate's headers set. You then call the vendor's
client as you always did. Each has the same four things:

| | Python | TypeScript | Java |
|---|---|---|---|
| Install | `pip install "tollgate-client[openai]"` or `[anthropic]` | `npm install tollgate-client openai` or `@anthropic-ai/sdk` | the Maven dependency below |
| A client | `tollgate.openai()`, `tollgate.anthropic()` | `openai()` from `tollgate-client/openai`, `anthropic()` from `tollgate-client/anthropic` | `TollgateOpenAI.builder()`, `TollgateAnthropic.builder()` |
| Headers for one call | `tollgate.headers(data_class=, user=, dry_run=)` | `headers({ dataClass, user, dryRun })` | `Tollgate.DATA_CLASS`, `Tollgate.USER`, `Tollgate.DRY_RUN` |
| What Tollgate said | `tollgate.receipt(raw)` | `receipt(response)` | `TollgateOpenAI.receipt(response)` |
| Why it refused | `tollgate.refusal(error)` | `refusal(error)` | `TollgateOpenAI.refusal(e)` |

- **The address** is the argument, then `TOLLGATE_BASE_URL`, then
  `http://localhost:8080`. **The key** is the argument, then
  `TOLLGATE_API_KEY`. In Java, `builder()` reads both variables, and
  `builder(baseUrl, apiKey)` reads neither: an empty address becomes
  `http://localhost:8080`, and an empty key is an error. The libraries never
  read `OPENAI_API_KEY` or `ANTHROPIC_API_KEY`, so a vendor key can't reach
  Tollgate by mistake.
- **The user** goes in `X-Tollgate-User`. It is recorded when the tenant's
  `userHeader` names that header, which is what `tollgate init` writes.
- **A receipt** has the trace ID, the cost, the most the call could have cost
  and the output cap if the budget lowered it. The costs, and dry runs, are
  for tenants with `showCosts: true`. **A refusal** has the code, the
  message, the status, the trace ID and `Retry-After`, and is empty for an
  error that didn't come from Tollgate.
- **A library decides nothing.** It doesn't retry, log or cache, and a call
  made without it gets the same guards, budget and audit.
- **Python** needs 3.10 or later and has `async_openai` and `async_anthropic`
  too. **TypeScript** needs Node 22 or later. **Java** needs 17 or later,
  and `builder()` gives you the vendor's own builder.
- **Each package is built from this repo** by the release workflow, and
  PyPI and npm show where each one was built
  ([the security policy](../SECURITY.md)).
- Streams are the vendor's own. A stream's charged cost is a comment line the
  SDKs drop, so a client reads it from the audit trail or the usage report.

```xml
<dependency>
  <groupId>io.github.jamal-akhras</groupId>
  <artifactId>tollgate-client</artifactId>
  <version>0.1.0</version>
</dependency>
```

The OpenAI and Anthropic Java SDKs are optional dependencies of
`tollgate-client`, so add the one you use.

```ts
import { openai } from 'tollgate-client/openai';
import { receipt, refusal } from 'tollgate-client';

const client = openai();
const { data, response } = await client.chat.completions
  .create({ model: 'qwen3', messages })
  .withResponse();
console.log(receipt(response).traceId);
```

```java
OpenAIClient client = TollgateOpenAI.builder().build();
try {
    client.chat().completions().create(params);
} catch (OpenAIServiceException e) {
    TollgateOpenAI.refusal(e).ifPresent(why -> log.warn("{} {}", why.code(), why.traceId()));
}
```
