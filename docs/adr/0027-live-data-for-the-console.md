# ADR 0027: Live data for the console comes from the audit trail

Date: 2026-11-16 · Status: Accepted, amends
[ADR 0017](0017-metrics-traces-and-usage.md)

## Context

The console has to show what is happening now: calls flowing from tenants
through their rules to providers, throughput, tokens, spend, refusals,
latency and budgets. ADR 0017 sends those numbers to Prometheus and draws
them in Grafana. Many operators run neither, and a first-time user has only
a browser.

The numbers have to come from inside the process, under three rules:

- **A request must never wait for the console.** Nothing on the request
  path may block, lock or allocate for it.
- **Its cost must be bounded** whatever the traffic and however many
  browsers are open.
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
| Totals for each 5 seconds: calls sent, refused and failed, tokens in and out, cost, a 40-step latency histogram | the last hour, 720 entries | fixed |
| Per tenant, model and provider, for each minute: calls, refusals by code, failures, fallback attempts, tokens, cost, values redacted | the last 15 minutes | 2,000 combinations; more are summed under `other` |
| Per tenant and guard, for each minute: allowed, refused, would have refused | the last 15 minutes | 2,000 combinations |
| The most recent calls, each with its steps and outcome | 200 | fixed |
| The last seal | 1 | fixed |

It is a few megabytes at the bounds and is lost on restart. History is the
audit trail's job, through the usage report.

### Two endpoints

- **`GET /admin/v1/live?minutes=15`** returns one snapshot: the 5-second
  totals for the last 1 to 60 minutes, the per-flow and per-guard totals of
  the last 15 minutes with what happened in the last 10 seconds, calls in
  flight to each provider, rate-limit refusals for each tenant since the
  start, and the last seal.
- **`GET /admin/v1/activity?limit=50`** returns the most recent calls,
  newest first: the fields of the intent and the outcome, without the HMACs.
- **The reader publishes an immutable snapshot each second.** Requests only
  read it. Ten open consoles cost the same aggregation as one.
- **With the audit trail off both answer `409` `audit_off`,** like the usage
  report.

### What the audit trail doesn't hold

Three numbers are read from the meters that already exist, when a snapshot
is built: calls in flight (`tollgate.providers.in_flight`), rate-limit
refusals (`tollgate.limits.refused`) and busy providers
(`tollgate.providers.busy`). A rate-limited call is refused before the body
is read and is never audited (ADR 0015), so the trail can't count it.

### The browser asks; the server doesn't push

- **The console polls** the snapshot every 2 seconds on the flow page and
  every 5 on the others, and stops while its tab is hidden.
- **No connection stays open.** A poll is one small request on a virtual
  thread, with nothing to time out, limit or clean up.

### What drives the moving lines

A line from a tenant through its policy to a provider moves while that flow
has calls in the snapshot's last 10 seconds. It is green for calls sent,
amber for fallback attempts and red, ending at the guard, for refusals. The
movement is a CSS animation; the data only switches it on and off. The
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
  an hour. "Today" and "7 days" come from the usage report.
- **A call refused for a rate limit shows only as a count,** not as a line
  in the activity list.
- **In asynchronous audit mode** the console is as late as the audit queue.
- **When the trail moves to Postgres or Kafka,** the reader becomes a query
  or a consumer, and the endpoints stay.
