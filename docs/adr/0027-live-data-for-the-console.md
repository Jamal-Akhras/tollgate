# ADR 0027: Live data for the console comes from the audit trail

Date: 2026-11-16 · Status: Accepted, amended 2026-11-26, amends
[ADR 0017](0017-metrics-traces-and-usage.md)

## Amendment (2026-11-26)

The first version fed the flow page and its 5-second totals. The metrics page
needs longer windows, error classes, a comparison with the window before and
latency by provider, tenant and model, and it needs them from the same reader.
What changed:

- **`GET /admin/v1/live` takes `step`,** 5, 15 or 60 seconds, 5 by default.
  Anything else answers `400`. A bucket is the sum of the 5-second ones,
  aligned to multiples of the step in epoch seconds, and its percentiles come
  from the summed histogram, not from the smaller percentiles.
  `bucketSeconds` shows the step.
- **Calls answered with an upstream status of 400 to 499 or 500 and above are
  counted** in `clientErrors` and `serverErrors`, on each bucket and each
  flow. They are subsets of `sent`, which keeps its meaning.
- **The per-flow and per-guard counts follow `minutes`** instead of always
  covering 15, and they are kept for 60 minutes instead of 15. With the
  default `minutes=15` the answer is what it was.
- **Three fields are added to the snapshot:** `totals` for the window,
  `previous` for the window of equal length just before it (null unless this
  instance was already running at its start), and `latency`, the p50 and p95
  of responded calls by provider, tenant and model. A group's percentiles
  come from its own summed histogram, because percentiles can't be added up.
  The top-level `p50Millis` and `p95Millis` stay the last 5 minutes.
- **The 5-second totals are kept for 120 minutes,** 1,440 entries instead of
  720, so that `previous` can be served for a 60-minute window.
- **Each flow-minute carries a 40-step latency histogram** for the grouped
  percentiles. It is allocated only once the flow has a responded call.
- **The sums move from the reader to the request.** The reader still reads
  the trail once and publishes an immutable snapshot each second, but the
  snapshot now holds the 5-second and per-minute counts, and a request sums
  the ones inside its window. That is at most 1,440 buckets and the flows of
  60 minutes, and a request never changes the snapshot. The counts a snapshot
  shares with the reader are copied before the reader changes them again.
- **The memory bound grows with the longer keeping, and the cap on
  combinations falls from 2,000 to 500 a minute** for flows and for guards,
  with the overflow still summed under `other`. At 500 combinations for 60
  minutes, with every combination answering in every minute, a flow-minute is
  about 856 bytes with its histogram and a guard-minute about 220, which is
  roughly 33 MB at the bound. These are estimates, not measurements. Under
  steady traffic the 1,440 buckets add about 0.9 MB; a gateway with a few
  tenants, models and providers keeps little more than that.
- **Three things are deliberately not there.** Time to first byte and the
  state of the circuit breaker are not in the audit record. Rate-limit
  refusals are never audited (ADR 0015), so `limited` stays a count for each
  tenant since the start and has no bucket.
- **The meters read are two, not three.** Calls in flight and rate-limit
  refusals are read, once for each request; `tollgate.providers.busy` was never
  read. The sentence under "What the audit trail doesn't hold" now says what
  the code does.
- **Round two, after review.** `totals`, `flows`, `guards` and `latency` cover
  one span: the last `minutes` minute slots, the unfinished current minute
  included, clipped to the instance's start. `totals` is the exact sum of the
  5-second buckets in that span whatever `step` is, and `previous` is the
  `minutes` complete slots before it. `totals` and `previous` carry `seconds`,
  how long the sums cover, because the current minute is unfinished and any
  comparison or rate must be over `seconds`. A new `clientClosed` count on
  buckets, flows and totals is the calls that ended because the client went
  away, a subset of `failed`, which keeps its meaning. The cap on
  combinations is 500, as above.

Everything else stands as first accepted.

## Context

The console has to show what is happening now: calls flowing from tenants
through their rules to providers, throughput, tokens, spend, refusals,
latency and budgets. ADR 0017 sends those numbers to Prometheus and draws
them in Grafana. Many operators run neither, and a first-time user has only
a browser.

The numbers have to come from inside the process, under three rules:

- **A request must never wait for the console.** Nothing on the request
  path may block, lock or allocate for it.
- **Its cost must be bounded** whatever the traffic. A request sums the
  published snapshot inside its window, so each open browser costs one such
  bounded sum for each poll, and none of them reads the trail.
- **The privacy rules don't bend.** No prompts or answers, and no user IDs
  (ADRs 0007, 0017, 0023).

Tollgate already writes down everything the console wants to show. Each
call's intent holds the tenant, key, model, provider, every guard step with
its verdict and time, the decision and the reservation. Each outcome holds
the result, tokens, cost and duration. The file is flushed within a second,
and it has no content in it by design.

## Decision

### One reader, off the request path

- **The server follows the audit trail's newest file.** One background
  thread wakes once a second, reads the lines added since last time and
  updates a window of counts in memory. It reads the file like `audit
  verify` and the usage report do; it shares nothing with the writer.
- **The request path is not touched.** No hook, listener or queue is added
  to the gateway. A fault in the console's reader can't refuse, slow or
  miscount a request.
- **It starts where the file ends** when the server starts, and moves to a
  newer file when one appears. The window holds this run only.
- **It is bounded.** One pass reads at most 4 MB. If more is waiting, it
  jumps to the end of the file and the snapshot says that records were
  skipped. A line that doesn't parse is skipped; judging the trail is
  `audit verify`'s job.
- **It runs only when the console is on** (`tollgate.console.enabled`,
  ADR 0025) and the audit trail is on.

### What is kept, and for how long

| Kept | Size | Bound |
|---|---|---|
| Totals for each 5 seconds: calls sent, refused and failed, sent calls answered with a 4xx or a 5xx, tokens in and out, cost, a 40-step latency histogram | the last 2 hours, 1,440 entries | fixed |
| Per tenant, model and provider, for each minute: calls, calls answered with a 4xx or a 5xx, refusals by code, failures, fallback attempts, tokens, cost, values redacted, a 40-step latency histogram | the last hour | 500 combinations; more are summed under `other` |
| Per tenant and guard, for each minute: allowed, refused, would have refused | the last hour | 500 combinations |
| The most recent calls, each with its steps and outcome | 200 | fixed |
| The last seal | 1 | fixed |

It is lost on restart. The 5-second totals are about a megabyte. The counts
for each flow and guard depend on how many combinations are used, and are
about 33 MB at the bounds (an estimate). History is the audit trail's job, through the
usage report.

### Two endpoints

- **`GET /admin/v1/live?minutes=15&step=5`** returns one snapshot: totals for
  each 5, 15 or 60 seconds over the last 1 to 60 minutes, sums for that
  window and the one before it, latency by provider, tenant and model, the
  per-flow and per-guard totals of the same minutes with what happened in the
  last 10 seconds, calls in flight to each provider, rate-limit refusals for
  each tenant since the start, and the last seal.
- **`GET /admin/v1/activity?limit=50`** returns the most recent calls,
  newest first: the fields of the intent and the outcome, without the HMACs.
- **The reader publishes an immutable snapshot each second.** Requests only
  read it and sum the part inside their window. Ten open consoles cost ten
  such sums, each bounded as above, and still no second pass over the trail.
- **With the audit trail off both answer `409` `audit_off`,** like the usage
  report.

### What the audit trail doesn't hold

Two numbers are read from the meters that already exist, once for each
request: calls in flight (`tollgate.providers.in_flight`) and rate-limit
refusals (`tollgate.limits.refused`). A rate-limited call is refused before
the body is read and is never audited (ADR 0015), so the trail can't count it.

### The browser asks; the server doesn't push

- **The console polls** the snapshot every 2 seconds on the flow page and
  every 5 on the others, and stops while its tab is hidden.
- **No connection stays open.** A poll is one small request on a virtual
  thread, with nothing to time out, limit or clean up.

### What drives the moving lines

A line from a tenant through its policy to a provider is drawn for every flow
with calls in the window. It moves slowly, and faster while the flow had calls
in the last 10 seconds. It is green for calls sent,
amber for fallback attempts and red, ending at the guard, for refusals. The
movement is a CSS animation; the data only sets its speed. The
picture is at most 3 seconds behind: a second for the trail's flush, and up
to two for the poll.

### Privacy

- **Nothing reaches the browser that the trail doesn't hold,** so no prompt
  and no answer.
- **A user is shown only as "attributed" or not.** The HMAC stays in the
  usage report `by=user`, where ADR 0023 put it.
- **Key IDs appear in the activity list,** as they do in the usage report
  `by=key`. They are not secrets.
- **A refusal's message is shown as recorded.** It can hold a tool's name,
  which is the client's text (ADR 0024).
- **No metric is added or changed.** Port 9090 shows what it showed before.
  The live endpoints are behind the admin token.

## Alternatives considered

- **Sampling the Micrometer registry each second.** No file reads, and it
  works with the audit trail off. But meters don't say which guard refused,
  hold no per-call detail for the activity list, and give percentiles only
  through histogram arithmetic. It would need the audit trail as well.
- **A listener in the gateway,** called for each intent and outcome. No
  parsing, and no delay. It puts console code on the request path or on the
  audit writer's thread, where a slow or failing listener harms the one
  component that must not fail, and it adds a public interface to the
  starter that has to stay compatible.
- **Server-sent events or a WebSocket.** Smoother by a second. Each open
  console holds a connection, which needs a cap, heartbeats, timeouts and
  cleanup, and breaks behind proxies that buffer. The payload is the same,
  so this can change later without changing the data.
- **Reading the trail only when a browser asks.** Nothing runs when nobody
  looks. But the console would open on empty charts every time.
- **An embedded time-series store,** so history survives restarts. A second
  record of usage next to the audit trail, which ADR 0017 already turned
  down.
- **Bundling Prometheus and Grafana in a compose file.** It works, and the
  dashboard exists. It is not "open the console".

## Consequences

- **Live numbers with no other system,** from the same records `audit
  verify` vouches for.
- **One thread reads and parses each audit line a second time.** On an idle
  gateway that is one file-size check a second. Under load it is bounded by
  the 4 MB pass. The first performance report should measure it.
- **With `audit.mode: off` the console has no live view,** only config and
  budgets.
- **The live view starts empty after a restart** and never shows more than
  an hour, plus the hour before it for comparison. "Today" and "7 days" come
  from the usage report.
- **A call refused for a rate limit shows only as a count,** not as a line
  in the activity list.
- **In asynchronous audit mode** the console is as late as the audit queue.
- **When the trail moves to Postgres or Kafka,** the reader becomes a query
  or a consumer, and the endpoints stay.
