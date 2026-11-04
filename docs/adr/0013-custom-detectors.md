# ADR 0013: Let operators define their own detectors

Date: 2026-11-04 · Status: Accepted

## Context

The built-in detectors ([ADR 0012](0012-redact-personal-data.md)) find values
that look the same everywhere: email addresses, card numbers, API keys. Most
organisations also have values of their own that should not leave, or should
not leave unmasked:

- identifiers with a house format, such as `CUST-004211` or a patient number
- account numbers that only mean something next to a word like "account"
- names that are not personal but are secret, such as an unannounced product
  or a client's name

Tollgate cannot ship these. The people running it can describe them.

## Decision

### Detectors are defined once, at the top of the config

```yaml
detectors:
  customer-id:
    pattern: "CUST-[0-9]{6}"
  account-number:
    pattern: "(?i)account(?: number| no\\.?)?[ :#]*(?<value>[0-9]{8})"
  codenames:
    terms: [Bluebird, Project Falcon]
  clients:
    terms: [Acme Holdings, Globex]
    caseSensitive: true

policies:
  default:
    redact:
      email: [internal, confidential]
      customer-id: [internal, confidential]
      account-number: [internal, confidential]
      clients: [confidential]
    refuse:
      secret: [public, internal, confidential]
      codenames: [public, internal, confidential]
```

- **A detector is either a `pattern` or a list of `terms`,** never both.
- **Policies use them by name** in `redact` and `refuse`, exactly like the
  built-in ones, so the same detector can be redacted for one data class and
  refused for another.
- **Names follow the usual name rules** and may not reuse a built-in name. The
  placeholder is the name in capitals with `-` and `.` turned into `_`, such
  as `[CUSTOMER_ID_1]`. Two detectors whose placeholders would clash are
  rejected.

### Patterns

- **Java regular expressions,** compiled when the config loads. A pattern that
  does not compile, or that matches empty text, stops the config from loading
  with the reason.
- **A named group `value`** limits the replacement to that part of the match,
  so `account number 12345678` becomes `account number [ACCOUNT_NUMBER_1]`.
  The words around the value make the pattern precise, and the model still
  reads them.

### Terms

- **Whole words only:** `Globex` matches in "Globex's offer" but not in
  "Globexia". Runs of spaces inside a term match any run of whitespace, line
  breaks included. A term must start and end with a letter or digit.
- **The longest term wins** when several start at the same word, so
  `Acme Holdings` is found whole even when `Acme` is also listed.
- **Case-insensitive** unless `caseSensitive: true`. The original spelling is
  what comes back.
- **At most 10,000 terms** per detector, so a list stays a list and not a
  database. Terms are kept in a character tree, so the scan costs the same
  however many terms share a first word.

### A pattern cannot stall the gateway

A badly written pattern can backtrack for minutes on the wrong input, and
whoever writes the config is not always whoever reads the requests.

- **Every request gets a scan budget,** `tollgate.max-scan-time`, default
  200 ms, shared by every text in the request. Custom patterns check it
  before each text and every 1,024 characters they read.
- **A request that runs out of budget is refused** with `500` and the code
  `redaction_timeout`, naming the detector, and the intent records the
  refusal as the `personal-data` step. Retrying cannot help, since the
  pattern itself is at fault, so the status says the fault is the gateway's.
  Tollgate never sends text it could not finish checking.
- **The log warns** with the detector's name, so the pattern can be fixed.

The built-in detectors and terms are linear in their input, so only custom
patterns need the budget.

### The audit trail

- **Counts are keyed by detector name,** built-in or custom, in name order.
  The version 2 record format from ADR 0012 has not been released, so it
  changes before release: the counts are sorted by name instead of in the
  order of ADR 0012's table.
- **The config version already covers the detectors,** because it hashes the
  whole file, so the trail says which definitions decided each request.

## Alternatives considered

- **A linear-time regex engine such as RE2/J.** No pattern could ever stall,
  but core depends only on the JDK ([ADR 0002](0002-module-boundaries.md)),
  and RE2 syntax lacks lookarounds, which precise patterns often need. The
  scan budget gives the same guarantee to the gateway.
- **Validators such as checksums for custom patterns.** Useful, but each one
  is code. A pattern with surrounding words gets most of the precision.
- **Terms loaded from a file.** Simpler to maintain for long lists, but it
  splits the config across files and the config version would no longer
  cover everything. Possible later.
- **Detectors defined inside each policy.** Policies would repeat the same
  definitions, and the same values would get different placeholder names.

## Consequences

- **Teams can protect what only they know about,** with the same restore,
  streaming and audit behaviour as the built-in detectors.
- **A bad pattern refuses requests instead of hanging them,** and says which
  pattern to fix.
- **False matches are the operator's to tune,** per detector and data class.
