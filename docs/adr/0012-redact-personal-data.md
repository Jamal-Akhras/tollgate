# ADR 0012: Redact patterned personal data and secrets, and restore them in answers

Date: 2026-11-04 · Status: Accepted

## Context

Data classes ([ADR 0008](0008-harden-the-request-path.md)) decide where a
request may go, but not what is in it. A request that may go to a hosted
model still carries every email address, card number or API key a user pasted
in. Most of these values do not help the model answer; they only leave the
machine.

Many of them have a recognisable shape and can be checked: card numbers pass
the Luhn check, IBANs carry a mod-97 check digit, and API keys start with
known prefixes. Names and addresses have no shape and need language models to
find, which is later work.

A redacted value has to come back. When a user asks "draft a reply to
jane@example.com", the answer should say jane@example.com, not a placeholder.
Tool calls matter even more: a model that calls `send_email` with a
placeholder would send nothing useful.

## Decision

### What is found

Pattern detectors only, each tuned to avoid false matches, since a redacted
order number breaks a conversation:

| Detector | Matches |
|---|---|
| `secret` | API keys and tokens with known prefixes (OpenAI, Anthropic, GitHub, GitLab, Slack, AWS, Google, Tollgate's own `tg_`), and PEM private keys, even when cut off |
| `email` | Addresses with a dotted domain |
| `card` | 13 to 19 digits, spaced or not, that pass the Luhn check and start and run like a real card brand |
| `iban` | IBANs with the right length for their country and a valid mod-97 check |
| `national-id` | UK National Insurance numbers and US Social Security numbers, with their invalid ranges excluded |
| `phone` | Numbers with a `+` country code and 8 to 15 digits, or 10 or 11 digits in a common national grouping |
| `ip` | IPv4 and IPv6 addresses, excluding loopback, unspecified, broadcast and documentation ranges |

- **Where two values overlap,** the one that starts first wins, then the
  longer one, then the detector higher in this table.
- **Bare digit runs are not phone numbers.** A national number needs its
  usual grouping, and a compressed IPv6 address needs two written groups, one
  of them three or more digits long, so `a[1::2]` in code is left alone.
- **No input can make a detector backtrack without limit** or overflow the
  stack. The tests scan a megabyte of each hostile shape, such as an email
  address with half a million domain labels, under a time limit.

### Policies choose, per data class

Like features, each detector is switched on for named data classes:

```yaml
policies:
  default:
    redact:
      email: [internal, confidential]
      card: [public, internal, confidential]
      iban: [internal, confidential]
    refuse:
      secret: [public, internal, confidential]
```

- **`redact`** replaces each value before the request leaves and restores it
  in the answer.
- **`refuse`** stops the request with `403`, naming the detector but never the
  value. This suits secrets, which no model should see at all. A refused
  value is found anywhere in the request, even inside a value that is being
  redacted.
- **A detector is redacted or refused for a data class, never both.** The
  config is rejected otherwise.
- **Nothing is redacted unless a policy says so.** The config that
  `tollgate init` writes refuses secrets and redacts card numbers for every
  class.
- **The scan runs after the other guards** as the `personal-data` step, so it
  appears in the decision trace with its time, and dry runs refuse what a real
  call would.

### How values are replaced

- **Placeholders** look like `[EMAIL_1]` or `[CARD_2]`. Models read them as
  stand-ins and usually repeat them as written.
- **The same value gets the same placeholder** throughout a request, numbered
  by first appearance. A conversation's history therefore redacts the same
  way on every turn, and prompt caching keeps working.
- **A placeholder the user typed** is itself replaced, with the next free
  number, so it comes back exactly as written and never as someone's address.
- **Where:** the text of messages, system prompts, tool results, plain-text
  documents and tool-call arguments, in both endpoint formats. Arguments that
  are JSON are redacted value by value and rewritten only if something
  changed. Not tool definitions, images, PDFs or model names. Anthropic
  `thinking` blocks are left as they are, because the provider rejects a
  changed signed block.

### How values come back

- **Answers:** every placeholder Tollgate issued for the request is replaced
  in the text, the reasoning text of OpenAI-format answers, tool-call
  arguments and provider error messages, so the client sees the original
  values. A value restored inside JSON arguments is escaped as JSON.
  Anthropic thinking blocks are left as the model wrote them.
- **Streams:** a placeholder can be split across events. When an event ends
  with the start of a placeholder Tollgate issued, that text is held back and
  sent with the next event. Held text is released before a content block or
  choice finishes, before `[DONE]`, and when the stream ends early, so
  nothing is lost. Clients see the restored value a few characters later.
- **Only this request's placeholders** are restored. A `[EMAIL_7]` the model
  invents stays as written.
- **The mapping lives in memory** for the length of the call only.

### The audit trail records what was found

- **How many values of each kind** were replaced, such as `email: 2`, is
  added to the intent. Values are never recorded.
- **What left:** the intent's `requestHmac` stays over the request as
  received, and a `sentHmac` covers the body sent to the provider, so the
  trail proves what left the machine.
- **This is version 2 of the record format,** for intents with redactions
  only. The binary encoding starts with version byte 2 and, after the
  reservation, adds the number of detectors, each detector's config name and
  count in the order of the table above, and the 32-byte `sentHmac`. In JSON:

  ```json
  {"v":2,"type":"intent",…,"reservation":null,"redactions":{"values":{"email":2,"card":1},"sentHmac":"e7a3…"}}
  ```

  Every other record keeps version 1, so earlier trails still verify, and
  `audit verify` and budget replay read both.
- **Refused requests record no redactions.** Their decision names the
  detector, and nothing was sent.

## Alternatives considered

- **Names and addresses by language model.** They are most of what people
  mean by personal data, but they need a model next to Tollgate. That is
  M2, as an optional detector.
- **Redact without restoring.** Simpler, but answers come back full of
  placeholders, and tool calls break.
- **Hashes or fake values instead of placeholders.** Fake values read
  naturally to the model, but they can be mistaken for real data and are
  hard to restore reliably.
- **Refusing streams when redaction is on.** It avoids the hold-back, but
  most chat clients stream, so redaction would be off in practice.
- **Recording redacted values encrypted in the audit trail.** That keeps the
  very data the feature exists to keep out.

## Consequences

- **Fewer values leave the machine,** with no change for applications.
- **Patterns miss things.** A card number written in words, or a key with an
  unknown prefix, goes through. The README says so plainly.
- **False matches are possible,** such as an order number that passes the
  Luhn check, or a version number shaped like an IPv4 address. The detector
  is then switched off for that data class.
- **Streams arrive slightly lumpier** near a possible placeholder.
- **The audit format gains version 2**, which `audit verify` and budget replay
  read.
