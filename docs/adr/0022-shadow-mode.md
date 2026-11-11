# ADR 0022: Shadow mode for guards

Date: 2026-11-11 · Status: Proposed, amends [ADR 0007](0007-audit-trail.md)

## Context

A new guard is a guess about real traffic. Turned on straight away, a wrong
guess refuses requests that should have gone through, and the people who
notice are the users. Teams roll out rules like this by watching them first:
the rule runs, records what it would have done, and changes nothing.

The audit trail is where that record belongs, because it is already where
Tollgate says why a request was allowed. A monitored refusal that only shows
in a log would be the one decision the trail can't prove.

## Decision

### Config

An entry under a policy's `guards:` may carry `mode: monitor`. The default is
`enforce`, and any other value fails the config load.

```yaml
policies:
  default:
    guards:
      - { type: request-size, maxBytes: 1048576 }
      - type: data-class
        mode: monitor
        regions: { public: [us, local], internal: [local] }
```

### Behaviour

- **A monitored guard runs like any other.** If it would refuse, its step is
  recorded with that verdict and the request goes on to the next guard.
- **The request's decision ignores monitored refusals.** An enforcing guard
  later in the list still refuses.
- **Only guards listed under `guards:` can be monitored.** The built-in
  request review, the `redact` and `refuse` detectors, budgets and rate limits
  always enforce. They protect data and money, and they have no rollout risk
  that a monitor would help with.
- **Fallback attempts** re-run the guards, so each attempt's intent carries its
  own steps, as today. A dry run records what a real call would.
- **A monitored `data-class` guard** still satisfies the rule that every
  tenant's policy has one, and startup logs a warning naming the policy,
  because data can then reach a region the guard would refuse.

### Audit: version 3 of the intent record

Version 3 is used only for intents with at least one monitored step, as
version 2 is used only for intents with redactions. All other records keep
their version, so trails written before this change verify unchanged.

- **Binary encoding:** version byte 3. Each step is the guard name, a mode
  byte (0 enforce, 1 monitor), the decision and the duration. After the
  reservation comes a presence byte for redactions, followed by the version 2
  redactions block when present.
- **JSON:** `"v":3`, and a monitored step carries `"mode":"monitor"`. A step
  without `mode` was enforced.
- **Readers:** `audit verify`, budget replay and the usage report accept
  version 3. A golden vector pins it, and the version 1 and 2 vectors don't
  change.

### Metrics and logs

- **`tollgate.guards.monitored`**, tagged tenant, model, provider and guard,
  counts each time a monitored guard would have refused.
- **A log line** at most once a minute for each tenant and guard.
- **`tollgate.requests` is unchanged:** its result is what actually happened.

## Alternatives considered

- **Monitoring a whole policy at once.** Simpler to switch, but rolling out
  one new guard would mean taking the others off too.
- **Recording monitored refusals in logs and metrics only.** No format change,
  but the audit trail would no longer explain every decision on a request.
- **Monitoring budgets and detectors too.** A monitored `refuse` lets a secret
  out, and a monitored budget lets spend through; neither can be taken back.
- **Writing every intent as version 3.** One version is simpler to read, but
  every existing trail and golden vector would change for a feature most
  requests don't use.

## Consequences

- **A new guard can run on real traffic before it refuses anything,** and the
  audit trail shows what it would have done.
- **The audit format gains version 3,** which every reader must accept.
- **A monitored `data-class` guard lets data reach regions it would refuse.**
  That is what monitoring means, and startup says so.
