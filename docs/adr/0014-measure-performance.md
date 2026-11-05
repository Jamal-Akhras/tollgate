# ADR 0014: How performance is measured and reported

Date: 2026-11-05 · Status: Accepted

## Context

DESIGN sets a target: under 2 ms of gateway overhead at p99 for
non-streaming calls with asynchronous audit, measured rather than assumed.
M1 ends with the first published report.

Latency numbers are easy to get wrong in ways that flatter the result:

- **A load generator that waits for each answer before sending the next**
  sends less when the gateway slows down, so the slow moments never make it
  into the percentiles.
- **Total latency hides the gateway's share** behind the provider's, which is
  seconds for a real model.
- **One short run on a warm laptop** measures the JIT compiler and the
  laptop's cooling as much as Tollgate.

## Decision

### Two kinds of measurement

- **Microbenchmarks** with JMH, in a `tollgate-benchmarks` module that is
  never published. They time each step on its own:
  - parsing, reviewing and forwarding a request, at 1 KB, 64 KB and 1 MB
  - the policy check
  - redaction, with every built-in detector, a 10,000-term list and a custom
    pattern, both when values are found and when nothing is
  - restoring a whole answer and a stream, event by event
  - the audit writer's capacity: records per second through encoding, HMAC
    and sealing, in memory and with each batch forced to disk. Asynchronous
    mode uses the same writer, so this is also the rate above which its
    10,000-record queue fills and requests are refused

  Each benchmark runs in two forks after warm-up iterations, and reports the
  mean with its error and the p99 of sampled calls.
- **Load tests** with Gatling against the real jar, which give the number
  clients feel.

### What overhead means

- **A stub provider** answers immediately with a fixed, realistic body: a
  1 KB chat completion, or for streams, 20 events 10 ms apart. It is a small
  Java server on virtual threads in the benchmarks module, so it is never the
  bottleneck.
- **Every scenario runs twice with the same load:** straight at the stub, and
  through Tollgate to the stub.
- **Overhead at a percentile** is Tollgate's latency at that percentile minus
  the direct latency at the same percentile. The report says so, since it is
  not the percentile of per-request differences.
- **For streams,** overhead is the extra time to the first event and to the
  last. Gatling times a stream once, so streams run twice: once closing after
  the first event, once waiting for `[DONE]`.
- **Gatling reports whole milliseconds.** Differences below a millisecond
  show in the microbenchmarks, not the load tests.

### How load is applied

- **An open model:** Gatling starts requests at a constant rate whatever the
  answers do, so a slow gateway queues requests instead of hiding them.
- **Rates:** the target scenario runs at 50, 200 and 500 requests per second
  with both request sizes; the others at 200 per second with the 2 KB
  request. A rate the machine cannot hold is reported as such, with its error
  rate, never dropped.
- **Each run** warms up for 30 seconds in a separate Gatling run, so warm-up
  requests never reach the measured statistics, then measures for 2 minutes,
  and is repeated 3 times. The report gives the median of the three runs and their
  spread. Tollgate is warmed once before the first run.
- **Requests** are realistic: a 2 KB chat request and a 32 KB one carrying
  history, from a tenant with a budget, so reservation and settling are
  included.

### The scenarios

| | Audit off | Audit async | Audit sync |
|---|---|---|---|
| No redaction | ✓ | ✓ (the target) | ✓ |
| Built-in detectors and a 1,000-term list | | ✓ | ✓ |
| Streaming, with redaction | | ✓ | |

Alongside them:

- startup time, and memory when idle and under load
- audit records per second in each mode, without HTTP
- `audit verify` and budget replay over a million records
- memory allocated per request, from the JVM's own allocation counter read
  through the metrics endpoint before and after each run

### Where it runs, and what is written down

- **The jar runs as shipped,** with `-Xmx512m` and the default collector.
- **Every report records:**
  - the CPU, cores, memory and OS
  - the JDK and its flags
  - the Tollgate commit
  - whether the machine was on mains power
  - whether the load generator shared the machine
- **The published figure comes from one machine with many cores,** each
  process pinned to its own: Tollgate to four performance cores, the stub to
  two more, and Gatling to the efficiency cores. Nothing competes for
  Tollgate's cores, and loopback adds no network jitter, which on Wi-Fi alone
  is several milliseconds at p99 and would swamp the difference being
  measured. It is also how Tollgate usually runs, next to the application it
  serves. The first report uses an i9-14900 (8 performance and 16 efficiency
  cores, 64 GB).
- **Runs on smaller machines are labelled** as sharing cores, such as a
  4-core laptop, where the overhead includes that contention.
- **The result against the target is stated plainly,** met or not.
- **`docs/performance.md`** holds the summary, the method and how to rerun
  every number. The raw Gatling reports stay out of the repository; their
  summary statistics are committed next to it.
- **Not in CI yet.** Nightly runs with regression thresholds need a quiet,
  fixed machine, which is M2.

## Alternatives considered

- **wrk2.** The strictest tool for latency percentiles, but it runs only on
  Linux and cannot measure time to the first streamed event.
- **k6.** Also an open model and light on memory, but scripted in JavaScript
  outside the Java build. Gatling's Java DSL lives next to the code it
  measures.
- **The load generator and Tollgate on separate machines.** Fairer in
  principle, but over a home network the jitter is larger than the overhead,
  and it measures the network rather than Tollgate.
- **A load generator of our own.** Lighter, but nobody has reason to trust
  its numbers.
- **JMeter or ab.** They wait for each answer by default, which hides exactly
  the latency this report exists to show.

## Consequences

- **The headline number is comparable** across machines and releases, and
  anyone can rerun it.
- **The full matrix takes about two hours,** plus about half an hour of
  microbenchmarks, so it is run before releases, not on every commit.
- **The benchmarks module adds JMH and Gatling** to the build, as test-only
  tools that never reach the jar or the image.
