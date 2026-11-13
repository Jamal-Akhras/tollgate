# ADR 0023: Attribute calls to a user

Date: 2026-11-13 · Status: Accepted, amends [ADR 0007](0007-audit-trail.md)
and [ADR 0017](0017-metrics-traces-and-usage.md)

## Context

A tenant is an application, and the audit trail says which tenant and which
key made each call. Some applications are shared: Open WebUI, LibreChat or an
internal portal sits in front of many people and calls Tollgate with one key.
Every call then looks the same, and nothing answers "who used this, and how
much?"

These applications can say who the person is. Open WebUI sends its user's ID
in a request header when asked to. Two things stand in the way of simply
recording it:

- **A header is a claim, not proof.** Anyone holding a tenant key can send any
  header. A header that every tenant could set would let one tenant write
  another person's name into the trail.
- **A user ID is personal data.** The audit trail keeps prompts and answers
  only as HMACs (ADR 0007), and metric tags hold only names from the config
  (ADR 0017). A user ID in plain text would break both rules.

## Decision

### Config

A tenant may name the request header that carries its user's ID. Without it,
no header is read.

```yaml
tenants:
  chat:
    models: [qwen3]
    dataClass: internal
    userHeader: X-OpenWebUI-User-Id
```

- **The name is the opt-in.** There is no default header and no global
  setting. A tenant that names none can't attribute calls, whatever it sends.
- **Any header name works,** so the same setting serves Open WebUI's own
  headers, a header of your choosing, or another application's.
- **It needs the audit trail.** A `userHeader` with `audit.mode: off` fails
  the config load, because the trail is the only place the user is recorded.

### Behaviour

- **The value is hashed before anything else sees it.** Tollgate takes the
  header's first value, trims it, and computes its HMAC-SHA256 under the audit
  HMAC key, the same key and function that hash prompts and answers. Only the
  HMAC is kept.
- **A request without the header goes through unattributed.** So does one
  whose value is blank. Attribution never refuses a request.
- **It decides nothing.** Guards, budgets and rate limits don't read the user.
  A tenant that lies in the header only mislabels its own calls.
- **It is trusted as far as the tenant is.** The tenant's key holder speaks
  for its users. Tollgate can't check that the person exists.
- **The header is not forwarded.** Tollgate builds the provider request's
  headers itself, as before, so the provider learns nothing about the user.
- **Fallback attempts and refusals carry the user too,** since each is its own
  intent.
- **Calls refused before the body is read stay unattributed:** a bad key or a
  rate limit is not audited at all (ADR 0015).

### Audit: version 4 of the intent record

Version 4 is used only for intents with a user. All other records keep their
version, so existing trails verify unchanged.

- **Binary encoding:** version byte 4. The layout is version 3's, with a mode
  byte for each step and a presence byte for redactions, followed by the 32
  bytes of the user's HMAC.
- **JSON:** `"v":4` and `"user":"<64 hex characters>"`. Steps may carry
  `"mode":"monitor"` and the line may carry `redactions`, as in version 3. A
  `user` on a version 1, 2 or 3 line fails verification, and so does a
  version 4 line without one.
- **Readers:** `audit verify`, budget replay and the usage report accept
  version 4. A golden vector pins it, and the earlier vectors don't change.

### The usage report

- **`by=user`** joins `tenant`, `model`, `key` and `day`, on the admin API and
  in `tollgate usage`. Each row is one HMAC. Calls without a user are summed
  in a row named `unattributed`.
- **`tollgate audit user <tollgate.yaml> <id>`** prints the HMAC an ID is
  recorded under, so an operator who knows a person's ID can find their row.
  It needs the audit keys in its environment, like the server.

### Metrics, traces and logs

- **No metric carries the user, hashed or not.** One series per person is
  unbounded, and ADR 0017 keeps tags to names from the config.
- **No span attribute and no log line carries it either.**

## Alternatives considered

- **Reading Open WebUI's headers by their fixed names.** Simpler to document,
  but it ties a core setting to one client, and Open WebUI lets the admin
  rename them.
- **A switch for every tenant at once.** One line of config, but it trusts
  the header from tenants that have no users behind them.
- **Recording the ID as it is,** as an option. Easier to read in a report,
  but the trail would hold personal data that can't be deleted without
  breaking the chain.
- **Verifying Open WebUI's signed token.** Open WebUI 0.13 can send the user
  as an HS256 JWT instead of plain headers. Checking it would prove the call
  came from that Open WebUI and not just from someone with the key. It needs
  a shared secret and a JWT parser, and is left for when a tenant's key is
  shared more widely than its application.
- **Reading the `user` field of an OpenAI request, or `metadata.user_id` of an
  Anthropic one.** Clients that set them could be attributed without a header.
  Those fields belong to the provider and are forwarded as sent, and Open WebUI
  doesn't set them. The audit field is the same either way, so a tenant
  setting for them can follow.
- **Hashing with the tenant's name mixed in,** so the same ID in two tenants
  can't be linked. It also stops an operator from summing one person across
  two tenants, which is the more common wish.
- **Budgets and limits per user.** Useful, but it turns a claim into
  something worth lying about. It waits for verified identities.
- **A metric per hashed user.** Dashboards would show people as series, and
  Prometheus would hold one series for everyone who ever sent a message.

## Consequences

- **A shared application's calls can be split by person,** from the signed
  trail, with no personal data in it.
- **The audit format gains version 4,** which every reader must accept.
- **A row is an HMAC, not a name.** Finding a person means hashing their ID
  with `audit user`. Whoever holds the HMAC key can do that for any ID, as
  they already can for a prompt.
- **Changing the HMAC key splits a person's history,** since their ID hashes
  to a new value.
- **The attribution is only as honest as the tenant.** It is a record of what
  the application said, not proof of who typed.
