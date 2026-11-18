# ADR 0034: An in-process mode for Java, after 0.1.0

Date: 2026-11-17 · Status: Accepted; nothing is built in 0.1.0

## Context

A Java application can already run Tollgate inside itself: the Spring Boot
starter serves the gateway's routes on the application's own port
(ADR 0021). The application's SDK client still calls it over HTTP, on
loopback. ADR 0021 left "an in-process Java API" as a later decision and
gave the reason: it is "a second way into every guard and budget, designed
in a day".

The question is back because the owner wants Tollgate usable "inline", like
a library. For Python and TypeScript that means the client libraries of
ADR 0031. For Java there is a second meaning: guards, budgets, routing and
audit running in the application's own process, with no HTTP hop at all.

What the code offers today:

- **The request path has one entry,** `Gateway.complete`, and it is already
  free of the Servlet API: the body as a stream, the headers as a function,
  the answer as status, body and headers, a stream as `open` and `write`.
- **It is not reachable.** The method and the constructor are
  package-private. Refusals are thrown as an exception that uses Spring's
  status type, and the error bodies are written by two Spring MVC classes.
  The module needs Spring MVC, Micrometer and Spring Boot's health module.
- **The official Java SDKs accept another transport.** An application can
  implement their `HttpClient` interface and build the client with it.

## Decision

### Not in 0.1.0

Nothing is built or made public for this in 0.1.0. A Java application that
wants Tollgate in its own process uses the starter and calls it on loopback,
with `tollgate-client` (ADR 0031) to make that one line.

It is built, if at all, after the first performance report (0.1.1) has said
what the loopback hop costs. If the hop is small next to a model call, the
starter may be all Java needs.

### What it will be

- **A transport, not a new API.** The in-process mode is an implementation of
  the vendor SDKs' `HttpClient` that hands each request to the gateway's
  request path in the same process:

  ```java
  try (Tollgate tollgate = Tollgate.open(Path.of("tollgate.yaml"))) {
      OpenAIClient client = TollgateOpenAI.inProcess(tollgate, "support-bot");
      client.chat().completions().create(params);
  }
  ```

  The application's code is the same as against the server. Moving from one
  to the other is the line that builds the client.
- **The same bytes.** A request is the body the SDK would have sent. The
  answer, a stream's events and an error are the bytes the HTTP gateway would
  have written, in the endpoint's format, with the same headers. There is
  one request path, entered two ways.
- **The caller names its tenant.** There is no key to check: the code calling
  is the application itself. The tenant still picks the models, the policy,
  the budget and the limits.
- **What is public:** opening it from a config file, closing it, reloading,
  and the two transports. Guards, the pipeline's steps and the budget store
  are not handed out one by one.
- **No Spring needed.** The request path, the config file, the audit sink
  and the error bodies move into a module without Spring MVC. The starter
  keeps the controllers, the filter and the auto-configuration, and depends
  on it. As in ADR 0021, the move is a pure move.
- **The same config file** and the same audit format, so `audit verify` and
  `usage` work on its trail.

### What it promises, and what it doesn't

- **It governs an application that wants to be governed.** The application
  holds the provider keys and can call a provider without Tollgate. Nothing
  in-process can stop that. A gateway on another host, holding the keys, can.
- **The audit trail is per process.** Each process writes its own directory,
  and two can't share one. Ten replicas are ten trails.
- **The trail shows that nothing was changed afterwards,** by anyone without
  the signing key. The application has that key. It does not show that the
  application recorded every call.
- **Budgets and limits are per process,** as they are per instance for the
  server today (ADR 0015).
- **There is no admin API and no console.**

### When it is the right choice

- One service that owns its own model calls and wants redaction, a budget,
  tool rules and a trail without running anything else.
- Batch jobs, command-line tools and tests.

It is the wrong choice when policy is set by someone other than the team
that writes the application, when several applications share a budget, or
when the trail has to convince a third party. Those need the server.

### How it differs from the starter

| | Starter (ADR 0021) | In-process mode |
|---|---|---|
| The application calls it | over HTTP, on loopback | by a method call |
| Other applications can call it | yes, with a tenant key | no |
| Tenant keys | checked | none |
| Needs | Spring Boot 4.1 on Spring MVC | any JVM application |
| Languages | any, over HTTP | Java, and what runs on the JVM |

## Alternatives considered

- **Making `Gateway.complete` public now.** It is one keyword. It would work
  only inside a Spring Boot application, and it would freeze a signature in
  the first release that was written for two controllers: raw bytes, a
  header function and a stream target, with refusals as an exception the
  caller can't read.
- **A guard API,** such as `tollgate.check(request)`, with the application
  calling the provider itself. The application would then have to settle the
  budget and record the outcome too, and one forgotten call leaves a
  reservation held and a trail without an ending.
- **Tollgate's own typed Java API** for chats. It is the native client of
  ADR 0031 again, with ADR 0005's objection.
- **Building it for 0.1.0.** The module split touches every file ADR 0021
  moved, days before the first release, for a mode whose main benefit
  hasn't been measured.
- **Never building it.** Possible. The decision above waits for the number.

## Consequences

- **0.1.0 has one way into the guards and budgets,** HTTP, as ADR 0021 left
  it.
- **Java applications get "inline" in 0.1.0 through the starter and the
  client library,** with a loopback hop.
- **When it is built, the request path gains a public entry** that has to be
  kept compatible, and the tests run on both.
- **The docs will have to say plainly what the in-process mode doesn't
  prove,** or it will be mistaken for the server.
