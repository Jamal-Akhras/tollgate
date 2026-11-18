# ADR 0031: Client libraries that wrap the official SDKs

Date: 2026-11-17 · Status: Accepted

## Context

An application uses Tollgate by pointing the OpenAI or Anthropic SDK at it:
a base URL and a tenant key (ADR 0005). That works, and it stays the promise:
"your SDKs, unchanged".

It leaves four things to the developer:

- **Getting the setup right.** Two base URLs (`/v1` and `/anthropic`), and a
  key that must not be mixed with a vendor key. With `ANTHROPIC_AUTH_TOKEN` or
  `OPENAI_API_KEY` in the environment, an SDK pointed at Tollgate by hand can
  send the vendor's key to the gateway.
- **Knowing Tollgate's headers:** the data class, the user, a dry run, the
  trace ID, the costs (ADR 0011, ADR 0023).
- **Telling refusals apart.** The SDKs type errors by status. A guard's
  refusal is a `PermissionDeniedError`, and a spent budget and a rate limit
  are both a `RateLimitError`.
- **Finding it.** A developer looks for `pip install` or `npm install`,
  not for a paragraph about base URLs.

The owner wants people to "import tollgate" and use it like the OpenAI or
Anthropic API, in Java, Python and TypeScript.

## Decision

### What a client library is

- **A thin wrapper.** It builds the official OpenAI or Anthropic client,
  already pointed at Tollgate, and hands it back. The application then calls
  `chat.completions.create` or `messages.create` on the vendor's own client,
  with the vendor's own types, streaming and tool helpers.
- **Tollgate stays the enforcement point.** The library decides nothing. A
  call made without it gets the same guards, budget and audit.
- **Nobody needs it.** Everything it does is a base URL, a key and headers,
  and the docs keep showing that form too.
- **Three languages:** Python, TypeScript and Java, with the same four things
  under the same names.
- **The names.** `tollgate` is taken on PyPI and on npm by other projects, so
  the Python and TypeScript packages are both `tollgate-client`:
  `pip install "tollgate-client[openai]"` and
  `npm install tollgate-client openai`. Python code still says
  `import tollgate`. The Java artifact is
  `io.github.jamal-akhras:tollgate-client`.

### The four things

1. **A client for each format.** `openai` gives the official OpenAI client
   with the base URL `<tollgate>/v1`; `anthropic` gives the official Anthropic
   client with `<tollgate>/anthropic`.
   - The gateway's address comes from the argument, then `TOLLGATE_BASE_URL`,
     then `http://localhost:8080`.
   - The key comes from the argument, then `TOLLGATE_API_KEY`. With neither,
     the call fails and says so. It never falls back to a vendor's variable.
   - The key is always passed to the vendor's client as its API key. The
     current SDKs stop reading their own credentials once a key is passed, so
     no vendor key can ride along. Their other variables still apply: headers
     from `ANTHROPIC_CUSTOM_HEADERS`, for one, reach Tollgate. In Java the library never
     calls the vendor's `fromEnv()`.
   - `data_class` and `user`, when given, become default headers.
   - Any other option is passed to the vendor's client untouched. In Java
     the library hands back the vendor's own builder, already filled in.
2. **`headers`:** the request headers for one call: `X-Tollgate-Data-Class`,
   `X-Tollgate-User` and `X-Tollgate-Dry-Run: true`.
3. **`receipt`:** what Tollgate said about a call, read from a response's
   headers: the trace ID, the cost, the most it could cost, and the output
   cap if the budget lowered it. Costs are the text Tollgate writes
   everywhere, such as `0.0412 USD`.
4. **`refusal`:** why Tollgate didn't answer, read from the SDK's own error:
   the code (`policy_denied`, `budget_exceeded`, `rate_limited` and the rest,
   from the `X-Tollgate-Error` header of ADR 0032), the message, the status,
   the trace ID and `Retry-After` in seconds. The message is the SDK's own
   error message, so its wording differs by language and SDK; programs should
   read the code. It gives nothing for an error that didn't come from Tollgate.

### How it looks

Python:

```python
import openai
import tollgate

client = tollgate.openai()                       # an openai.OpenAI
try:
    reply = client.chat.completions.create(model="qwen3", messages=messages)
except openai.APIStatusError as error:
    why = tollgate.refusal(error)
    if why and why.code == "budget_exceeded":
        ...

claude = tollgate.anthropic(data_class="confidential", user="u-17")
raw = claude.messages.with_raw_response.create(model="claude-sonnet", max_tokens=1024, messages=messages)
print(tollgate.receipt(raw).cost, raw.parse().content)
```

TypeScript:

```ts
import { openai } from 'tollgate-client/openai';
import { receipt, refusal } from 'tollgate-client';

const client = openai();                          // an OpenAI
const { data, response } = await client.chat.completions
  .create({ model: 'qwen3', messages })
  .withResponse();
console.log(receipt(response).traceId);
```

Java:

```java
OpenAIClient client = TollgateOpenAI.builder().build();   // an OpenAIOkHttpClient.Builder, from the environment
try {
    client.chat().completions().create(params);
} catch (OpenAIServiceException e) {
    TollgateOpenAI.refusal(e).ifPresent(why -> log.warn("{} {}", why.code(), why.traceId()));
}

AnthropicClient claude = TollgateAnthropic.builder("https://tollgate.internal", key)
        .putHeader(Tollgate.DATA_CLASS, "confidential")
        .build();
```

- **Python** also has `async_openai` and `async_anthropic`.
- **Java has no options type of its own.** `builder()` returns the vendor's
  builder, so every vendor setting is there already, and the header names
  are constants on `Tollgate`.
- **Java** has two classes, `TollgateOpenAI` and `TollgateAnthropic`, so an
  application needs only the vendor SDK it uses. Both SDKs are optional
  dependencies of `tollgate-client`, which needs Java 17 or later.
- **TypeScript** has two entry points for the same reason; `openai` and
  `@anthropic-ai/sdk` are optional peer dependencies.

### Dry runs, models and streams

- **A dry run** is the vendor's call with `headers(dry_run=True)`, made
  through the SDK's raw response. The receipt has the estimate's
  `cost_max`. The body is Tollgate's own JSON (ADR 0011) and is read from the
  raw response, not parsed as an answer.
- **Models** are the vendor's own `models.list()`, on either client
  (ADR 0032 adds the Anthropic list).
- **Streams** are the vendor's. The trace ID and `cost_max` are in the
  response headers. The charged cost of a stream is an SSE comment
  (ADR 0011), which the SDKs drop, so a client can't read it. It is in the
  audit trail and the usage report.

### The user header

The libraries send a user as `X-Tollgate-User`. A tenant that wants it
recorded sets `userHeader: X-Tollgate-User` (ADR 0023). The gateway still has
no default header; this is only the name the libraries and the docs agree on.

### What is in 0.1.0

All of the above, in the three languages, with ADR 0032 and ADR 0033. If the
date matters more, the Java library is cut first, then TypeScript: each is
its own package and none is needed by the server.

The cost is about five working days on the release date for all three,
about three for Python alone. The header of ADR 0032 goes into 0.1.0 either
way: it is half a day, and without it a library released later could not
give a refusal its code against a 0.1.0 gateway.

## Alternatives considered

- **A Tollgate-native client:** one `tollgate.chat(...)` for every model, with
  Tollgate's own request and response types. It is what a router ships. It
  needs a format of Tollgate's own and a translation to each provider's,
  which is the permanently changing surface ADR 0005 turned down, and it
  would trail every provider feature.
- **Typed exceptions,** such as `tollgate.BudgetExceeded`. The vendor's client
  raises the vendor's errors. Raising others means wrapping every method of
  two clients in three languages, or patching their HTTP layer. A reader for
  the error they already raise is a few lines and can't fall behind.
- **One `Tollgate` object holding both clients.** It reads well, but it
  imports both vendor SDKs, or loads them lazily in ways that differ per
  language. Two small functions are the same in all three.
- **A typed estimate for dry runs.** The receipt already carries the maximum
  cost. A type for the body can follow when someone needs the token counts.
- **Parsing costs into numbers.** A float would be wrong for money, and a
  decimal type differs per language. The text is exact and is what the audit
  trail shows.
- **Docs only, no packages.** It costs nothing and is where Helicone stopped.
  It leaves the environment-variable trap and the untyped refusals where
  they are.
- **Python first, the others later.** Cheaper, but the three are small and
  alike, and doing them together keeps their names the same.

## Consequences

- **A developer can `pip install`, `npm install` or add a Maven dependency,**
  and use Tollgate with the client they already know.
- **A refusal has a code in every language,** on both endpoints.
- **Three more things to release and keep current,** on two registries
  Tollgate has not published to before (ADR 0033).
- **Each library follows two vendor SDKs.** A breaking vendor release is
  found by the tests that run the real SDK against the real server.
- **The Java library brings Kotlin, OkHttp and Jackson 2** into an
  application through the vendor SDK it picks. Tollgate's other jars don't.
- **A stream's charged cost stays out of reach of a client.**
