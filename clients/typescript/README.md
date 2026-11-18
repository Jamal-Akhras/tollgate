# tollgate-client

The official OpenAI and Anthropic clients for Node, already pointed at a
[Tollgate](https://github.com/Jamal-Akhras/tollgate) gateway.

```sh
npm install tollgate-client openai
npm install tollgate-client @anthropic-ai/sdk
```

Install the SDK you use; both are optional peer dependencies. Node 22 or later.

```ts
import { openai } from 'tollgate-client/openai';
import { headers } from 'tollgate-client';

const client = openai();
const answer = await client.chat.completions
  .create({ model: 'qwen3', messages }, { headers: headers({ dryRun: true }) });
```

The gateway address is the `baseURL` option, then `TOLLGATE_BASE_URL`, then
`http://localhost:8080`. The key is the `apiKey` option, then
`TOLLGATE_API_KEY`. `anthropic()` from `tollgate-client/anthropic` works the
same way. `dataClass` and `user` are sent with every call.
