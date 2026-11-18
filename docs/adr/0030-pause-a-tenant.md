# ADR 0030: Pause a tenant

Date: 2026-11-16 · Status: Accepted

## Context

Sometimes one tenant has to stop now: an agent in a loop, a leaked key
nobody has traced yet, an application sending data it shouldn't. Tollgate
has three ways to stop a tenant today, and each is the wrong tool:

- **Remove its keys.** ADR 0015 says "to stop a tenant, remove its keys".
  The file keeps only each key's hash, so putting them back means finding
  the old lines. The applications get `401`, which reads as "your key is
  wrong", and someone goes off to make a new key.
- **Set its budget to zero.** Calls to free models still pass. Every
  refusal reads the body, runs the guards and writes an audit record.
- **Set a rate limit of zero.** The config rejects it (ADR 0015), and a
  `429` is what the OpenAI and Anthropic SDKs retry by themselves.

The console's list of controls has "Pause a tenant" as an emergency stop. It is a new setting, not an edit of an existing one, so it
gets its own decision. It is built after 0.1.0.

Two facts about the code shape it:

- **A request is checked in a fixed order:** the API key, then the tenant's
  rate limits, then the body is read, then the guards
  (`Gateway.complete`). A refusal before the body is read writes no audit
  record, because an intent needs the model, the route and the request's
  fingerprint (ADR 0015).
- **Each request runs under the config it started with.** A reload swaps a
  snapshot; calls in flight keep the old one.

## Decision

### The setting

```yaml
tenants:
  support-bot:
    models: [claude-sonnet, qwen3]
    paused: true
```

- **`paused` is `true` or `false`,** and `false` when left out.
- **Everything else about the tenant stays:** its keys, models, budget,
  limits and policy. Resuming is removing the line.
- **It lives in `tollgate.yaml` like every other rule,** so it survives a
  restart, is part of the config version, and is loaded by a reload.

### What a paused tenant gets

- **Every call with one of the tenant's keys is refused,** straight after
  the key is checked: chat completions, messages, and the model list. Dry
  runs too.
- **`403` with the code `tenant_paused`,** in the format of the endpoint
  that was called:
  - `/v1/chat/completions` and `/v1/models`:
    `{"error":{"message":"…","type":"invalid_request_error","param":null,"code":"tenant_paused"}}`
  - `/anthropic/v1/messages`:
    `{"type":"error","error":{"type":"permission_error","message":"…"}}`
- **The message is** "This tenant is paused by its operator, so no calls
  are accepted. The API key is valid."
- **No `Retry-After`.** Nobody knows when an operator will resume it.
- **Why `403`:** the SDKs retry `429` and `5xx` with backoff, and a paused
  tenant in a retry loop is the thing being stopped. They don't retry
  `403`. `401` would say the key is wrong, and it isn't.
- **A wrong key still gets `401`.** The pause is only revealed to someone
  holding a valid key.

### Calls already running

- **They finish.** A call that passed the key check before the reload runs
  to its end under the config it started with, fallback attempts included.
  A stream ends when the provider ends it, or at
  `tollgate.max-stream-duration` (10 minutes by default).
- **Pause stops new calls, not running ones.** Cutting streams needs a
  registry of calls in flight that Tollgate doesn't have. The console says
  how many calls are still in flight for the tenant's providers.

### What is recorded

- **A paused refusal is not written to the audit trail.** It happens before
  the body is read, like a wrong key and a rate limit (ADR 0015). There is
  no model, route or fingerprint to put in an intent. And the tenant being
  paused is often the one sending a flood; one record per refused call
  would let it fill the audit disk while it is supposed to be stopped.
- **The pause itself is evidence.** It is a line in the config, so it has a
  config version. Every audit record names the version that decided it, so
  the trail shows the last call before the pause and the first after the
  resume, and `config-history/` (ADR 0029) holds the file that said
  `paused: true`.
- **A counter, `tollgate.paused.refused`, tagged with the tenant,** counts
  the refusals. A warning is logged at most once a minute per tenant.
- **Startup and every reload log each paused tenant by name,** so a pause
  nobody remembers is visible.
- **A record of who paused it and when** waits for config changes in the
  audit trail (ADR 0029, "Not in this decision").

### With budgets, rate limits and fallback

- **Budgets:** nothing is reserved or charged for a refused call. What the
  tenant has spent stays counted, and its period runs on.
- **Rate limits:** the pause is checked before them. A refused call takes
  nothing from the request bucket and doesn't count as a concurrent call.
  When the tenant resumes, its buckets are where they were.
- **Fallback:** a pause is about the caller, not a model, so nothing falls
  back. It is decided before a route is chosen.
- **Monitor mode:** there is none. Like budgets and rate limits, a pause
  always enforces (ADR 0022).
- **Other tenants** are not affected, including ones that share the paused
  tenant's models and providers.

### Reload

- **A pause takes effect at the reload that loads it,** and so does a
  resume. Until then nothing changes.
- **A config with a paused tenant is valid in every other way.** The
  tenant's keys, models and policy are still checked when the file loads.

### Setting it

- **By hand, always:** add `paused: true` under the tenant and reload.
- **Through the admin API, when edits are on** (ADR 0029):
  `PUT /admin/v1/tenants/{tenant}/paused` with `{"paused": true}` or
  `{"paused": false}`. It follows the rules of every managed write, with
  one difference.
- **This route also accepts `If-Match: *`,** meaning "whatever is running".
  An emergency stop from `curl` shouldn't need a `GET` first. The file on
  disk must still be the version that is running, or the answer is `412`:
  a pause must not load somebody's half-finished hand edit.
- **In the console:** a Pause button on the tenant, on the flow page and in
  the config's tenant table. It asks first: "Pause support-bot? Every new
  call from its 2 keys will be refused until you resume it. Calls already
  running will finish." Resume doesn't ask. With edits off, the button
  shows the line to write.
- **A paused tenant is shown as paused everywhere it appears:** its card on
  the flow page, with its line ending in a cross; the tenant table; the
  config view (`paused` on the tenant). The live view reports how many
  calls were refused while paused, from the counter, as it does for rate
  limits.
- **No new command.** The command line doesn't talk to a running server and
  doesn't write the config; `keys create` prints a line to paste. The
  README gives the YAML line and the `curl` call.
- **The viewer token can't pause** (ADR 0026).

## Alternatives considered

- **Removing the tenant's keys,** as today. See Context.
- **A pause held in memory,** set through the admin API and gone at
  restart. Instant, and no file to write. But the running rules would no
  longer match the file or its version, a restart during an incident would
  quietly resume the tenant, and read-only deployments couldn't use it.
- **Writing an audit record for each refusal.** The trail would show every
  attempt. It needs an intent without a model, a route or a fingerprint,
  which is a new record version, and it hands a flooding tenant the audit
  disk.
- **`429` or `503`.** They say "try again", and the SDKs do.
- **`401`.** No new error code. It tells the application's owner the wrong
  thing.
- **Reading the body first, so the refusal can be audited as a guard
  refusal.** A paused tenant could then still make Tollgate read and parse
  16 MB at a time.
- **Pausing a key instead of a tenant.** Revoking a key already exists and
  is the right act for a leaked key. The stop is for "this application",
  which is the tenant.
- **A top-level `paused: [tenant, …]` list.** One place to look. But every
  other fact about a tenant is under the tenant.
- **One switch that pauses everything.** That is stopping the process, or
  the Connect app's switch.
- **Cutting the streams in flight.** A harder stop. It needs every running
  call registered by tenant; it can come later without changing this
  setting.

## Consequences

- **A tenant can be stopped and resumed without losing its keys,** in one
  line or one click.
- **Applications get a plain answer** that isn't retried and doesn't blame
  the key.
- **Refusals while paused are counted, not audited.** The trail shows the
  gap and the config versions around it, not each attempt.
- **A pause is as fast as a reload,** and calls already running finish,
  for up to the longest stream.
- **`Tenant` gains a field, the config reader a key, `GatewayException` a
  code and the request path one check** after the key is known. Nothing in
  the audit format changes.
- **The config view and the flow page gain a state** to draw.
- **`If-Match: *` exists on one route.** If it proves convenient it will be
  asked for on the others; that is a separate decision.
- **A forgotten pause looks like an outage to the tenant.** The startup and
  reload log lines, the flow page and the counter are what make it visible.
