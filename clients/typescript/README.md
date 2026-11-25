# tollgate-client

The official OpenAI and Anthropic clients for Node, already pointed at a
[Tollgate](https://github.com/Jamal-Akhras/tollgate) gateway. Tollgate is a
policy and audit gateway for LLM traffic.

```sh
npm install tollgate-client openai
npm install tollgate-client @anthropic-ai/sdk
```

Install the SDK you use; both are optional peer dependencies. Node 22 or later.

```ts
import { openai } from 'tollgate-client/openai';

const client = openai();
const reply = await client.chat.completions.create({ model: 'qwen3', messages });
```

The gateway address is the `baseURL` option, then `TOLLGATE_BASE_URL`, then
`http://localhost:8080`. The key is the `apiKey` option, then
`TOLLGATE_API_KEY`. `anthropic()` from `tollgate-client/anthropic` works the
same way. `dataClass` and `user` options are sent with every call. The user
goes in `X-Tollgate-User`, and is recorded when the tenant's `userHeader`
names that header.

## Headers

For one call, `headers()` builds the headers, and `dryRun` asks for a cost
estimate without calling the model. Costs and dry runs are for tenants with
`showCosts: true`:

```ts
import { headers } from 'tollgate-client';

await client.chat.completions.create(
  { model: 'qwen3', messages },
  { headers: headers({ dryRun: true }) },
);
```

## Receipt

`receipt(response)` reads what Tollgate said about a call: `traceId`, `cost`,
`costMax` and `outputLimited`. The two costs are `null` unless the tenant has
`showCosts: true`. Get the response with `.withResponse()`:

```ts
import { receipt } from 'tollgate-client';

const { response } = await client.chat.completions
  .create({ model: 'qwen3', messages })
  .withResponse();
console.log(receipt(response).traceId);
```

## Refusal

`refusal(error)` reads why Tollgate refused a call from the SDK's error: `code`,
`message`, `status`, `traceId` and `retryAfter`. It returns `undefined` for an
error that didn't come from Tollgate.

```ts
import { refusal } from 'tollgate-client';

try {
  await client.chat.completions.create({ model: 'qwen3', messages });
} catch (error) {
  const why = refusal(error);
  if (why) {
    console.log(why.code, why.traceId);
  }
}
```

The library retries nothing, logs nothing and decides nothing: a call made
without it gets the same guards, budget and audit.

## More

Source, documentation and the gateway itself: https://github.com/Jamal-Akhras/tollgate

The client libraries are described in
https://github.com/Jamal-Akhras/tollgate/blob/main/docs/client-libraries.md

Licensed under the Apache License 2.0:
https://github.com/Jamal-Akhras/tollgate/blob/main/LICENSE
