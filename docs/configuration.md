# Configuration

YAML is the source of truth. Tollgate reads its own `tollgate.yaml` rather
than Spring properties, so the server, the starter and the CLI load the same
model the same way.

```yaml
currency: USD
dataClasses: [public, internal, confidential, restricted]   # least to most strict

providers:
  anthropic: { format: anthropic, baseUrl: https://api.anthropic.com, apiKey: "${env:ANTHROPIC_API_KEY}", region: us }
  ollama:    { format: openai, baseUrl: http://gpu-box:11434/v1, region: local, concurrent: 2 }

models:
  claude-sonnet:
    provider: anthropic
    upstream: claude-sonnet-4-5
    price: { input: 3.00, output: 15.00, cacheRead: 0.30, cacheWrite: 3.75 }   # per million tokens
    maxOutputTokens: { default: 2048, cap: 8192 }
  qwen3:
    provider: ollama
    upstream: qwen3:32b
    price: free
    maxOutputTokens: { default: 4096, cap: 16384 }

tenants:
  support-bot:
    dataClass: confidential          # default; headers can only make it stricter
    models: [claude-sonnet, qwen3]
    policy: default
    userHeader: X-OpenWebUI-User-Id  # optional; the user is recorded as an HMAC
    budget: { limit: 50.00, period: monthly }
    limits: { requestsPerMinute: 60, tokensPerMinute: 200000, concurrent: 8 }

keys:
  - { id: k7Qm2xPa9bCd, tenant: support-bot, sha256: 3f1c…9ab2 }

policies:
  default:
    guards:
      - type: request-size
        maxBytes: 1048576
      - type: tool-names
        refuse: [run_command, "delete_*"]
      - type: data-class
        regions: { public: [us, eu, local], internal: [us, eu, local], confidential: [local], restricted: [local] }
    redact:                        # replaced before the call, restored in the answer
      email: [internal, confidential]
      iban: [internal, confidential, restricted]
      customer-id: [internal, confidential, restricted]
    refuse:                        # never sent
      secret: [public, internal, confidential, restricted]
      codenames: [public, internal, confidential, restricted]

detectors:                         # your own, next to the built-in ones
  customer-id: { pattern: "CUST-[0-9]{6}" }
  codenames: { terms: [Bluebird, Project Falcon] }

audit:                             # required; 'mode: off' must be written out
  mode: sync                       # sync | async | off
  directory: audit
  publicKey: MCowBQYDK2VwAyEA…
  signingKey: ${env:TOLLGATE_AUDIT_SIGNING_KEY}
  hmacKey: ${env:TOLLGATE_AUDIT_HMAC_KEY}
```

- **Tenants** may call only the models they list; any other model is reported as
  not found. Once `dataClasses` are defined, every tenant needs a default
  class, and a request header may move it to a stricter class only.
- **Guards** are `request-size`, `data-class` and `tool-names`. The data-class guard fails
  closed: a class with no listed regions, or a provider with no region, is
  refused. The `tool-names` guard refuses a request that defines a tool
  with a listed name (`*` matches any run of characters), or one not on an
  `allow` list. It sees tool definitions only, and the client chooses the
  names ([ADR 0024](adr/0024-refuse-tools-by-name.md)).
- **Users:** a tenant's `userHeader` names the request header that says which
  person is calling. Its value is recorded as an HMAC, and `usage --by user`
  groups by it. The header is the application's word, not proof
  ([ADR 0023](adr/0023-attribute-calls-to-a-user.md)).
- **Shadow mode:** a guard with `mode: monitor` records what it would have
  refused, in the audit trail and the `tollgate.guards.monitored` metric,
  without refusing, so a new guard can be measured before it is enforced
  ([ADR 0022](adr/0022-shadow-mode.md)).
- **Models** are the names applications ask for. Each maps to one provider,
  the provider's own model name, a price and an output-token cap. Every model
  has a known price before its first request. The price comes from the config,
  or from the reviewed catalog that ships with each release
  ([ADR 0009](adr/0009-price-catalog.md)). Nothing is fetched at runtime.
  A price can also give reasoning tokens and hour-long cache writes their own
  rates, and list long-context tiers that apply once a prompt reaches a size.
- **Secrets** are references, never values, and are never logged:
  `${env:NAME}`.
- **Tenant keys** never appear in configuration; only their hashes do.
  `tollgate keys create` prints a new key once, with the line to add.
- **Reloads** validate the new config and swap it in atomically, through
  `POST /admin/v1/config/reload`. An invalid config never replaces a running
  one, and each decision records the config version that made it. A reload
  that changes a tenant's budget period, or gives it its first budget, sums
  the audit trail again for that tenant, so its spend is not lost.
- **Edits through the admin API** are off unless `TOLLGATE_ADMIN_EDITS=file`
  ([ADR 0029](adr/0029-config-in-the-console.md)). Four routes change the file:
  a guard's mode, a tenant's budget, create a key and revoke a key. A write
  reads the file, changes the document, validates it as a reload does, writes
  it beside the original, moves it into place atomically and swaps the
  running config, under the reload's lock. It needs `If-Match` with the
  running config version: `428` without it, `412` if the version or the file
  is not the running one. The file is rewritten in a fixed layout and its
  comments are not kept; the first write saves the hand-written file as
  `tollgate.yaml.original`. Every version that ran, at startup, on reload and
  on write, is kept in `config-history/<version>.yaml` next to the file, so
  the version named in an audit record can be looked up. With edits on and a
  file or folder that can't be written, the server doesn't start.

## The config `tollgate init` writes

The config from the [quickstart](../README.md#quickstart), with comments added here:

```yaml
dataClasses: [public, internal, confidential]

providers:
  ollama: { format: openai, baseUrl: "http://localhost:11434/v1", region: local }

models:
  qwen3:
    provider: ollama
    upstream: "qwen3:8b"
    price: free
    maxOutputTokens: { default: 1024, cap: 4096 }

tenants:
  my-app:
    models: [qwen3]
    dataClass: internal
    userHeader: X-Tollgate-User
    # budget: { limit: 5.00, period: monthly }  for a priced model
    # limits: { requestsPerMinute: 60, concurrent: 4 }

keys:
  - { id: …, tenant: my-app, sha256: … }

policies:
  default:
    guards:
      - { type: request-size, maxBytes: 1048576 }
      - type: data-class
        regions: { public: [local], internal: [local], confidential: [local] }
    features:                                 # confidential data stays strict
      remote-images: [public, internal]
    redact:                                   # sent as [CARD_1], restored in the answer
      card: [public, internal, confidential]
    refuse:                                   # API keys and private keys never leave
      secret: [public, internal, confidential]

audit:
  mode: sync
  directory: audit
  publicKey: …
  signingKey: ${env:TOLLGATE_AUDIT_SIGNING_KEY}
  hmacKey: ${env:TOLLGATE_AUDIT_HMAC_KEY}
```

The `tollgate.env` next to it holds the audit keys, the admin token and `TOLLGATE_ADMIN_EDITS=file`. Secrets appear in the config only as `${env:NAME}` references.
