# ADR 0007: A signed, batch-chained audit trail

Date: 2026-10-29 · Status: Accepted, amended by [ADR 0009](0009-price-catalog.md)

## Context

Tollgate promises to answer four questions about any model call: what was sent,
who sent it, what it cost, and why it was allowed. The answers are only worth
something if a record cannot be changed, removed or added afterwards without
detection, and if a call cannot reach a provider without a record of it.

The trail must not store prompts, must not tie verification to how JSON
happens to be formatted, and must not make every request wait in line behind
one global hash chain.

## Decision

### What is recorded

- **Every request that reaches a decision gets an intent record.** That is
  every authenticated request whose model resolves to a route, including those
  refused by a guard or by the budget. Earlier rejections (bad key, malformed
  body, unknown model, wrong endpoint) are logged with the trace ID, not
  audited, so unauthenticated traffic cannot fill the trail.
- **Intent**, written after the reservation and before the provider call:
  trace ID, time, tenant, key ID, endpoint, model, provider, upstream model,
  data class, config version (SHA-256 of the config file), an HMAC of the
  request body as received, each guard step with its verdict and duration, the
  decision, and the reservation.
- **Outcome**, written after settlement, for allowed requests only: trace ID,
  time, result (`responded`, `unreachable`, `timed_out`, or `invalid_response`
  for a response Tollgate would not pass on; see
  [ADR 0008](0008-harden-the-request-path.md)), the provider's HTTP
  status, token usage, the settled cost, the call's duration, and an HMAC of
  the response body.
- **Prompts and responses are stored only as HMAC-SHA256 values** under a
  deployment key, so they cannot be recovered by guessing likely texts.
  Storing content is a later, opt-in feature.

### Record format

- The trail is a JSON-lines file with three line types: `intent`, `outcome`
  and `seal`. Every line has `"v": 1`.

  ```json
  {"v":1,"type":"intent","id":"7c0e…","time":"2026-11-02T14:03:10.512Z","tenant":"support-bot","key":"k7Qm2xPa4bZr","endpoint":"anthropic","model":"claude-sonnet","provider":"anthropic","upstream":"claude-sonnet-4-5","dataClass":"internal","configVersion":"3f1c…","requestHmac":"a91b…","steps":[{"guard":"request-size","decision":{"verdict":"allow"},"nanos":2100}],"decision":{"verdict":"allow"},"reservation":{"amount":"0.152300000","currency":"USD"}}
  {"v":1,"type":"outcome","id":"7c0e…","time":"2026-11-02T14:03:12.040Z","result":"responded","status":200,"usage":{"input":812,"output":304,"cacheRead":0,"cacheWrite":0},"cost":{"amount":"0.006996000","currency":"USD"},"nanos":1527000000,"responseHmac":"55d0…"}
  {"v":1,"type":"seal","batch":61,"records":214,"time":"2026-11-02T14:03:12.100Z","previous":"0d4a…","root":"9f2c…","key":"b81e44a0c3f2d917","hmacKey":"4c07a2e19b5d3f60","signature":"…"}
  ```

- **The JSON is a view; the hash is taken over a fixed binary encoding**
  defined in `tollgate-core`. Records will also live in Postgres, Kafka and S3,
  which do not keep the JSON text, and a trail must verify the same way from
  any of them. Fields are written in a fixed order: integers big
  endian, text as a 4-byte length and UTF-8, optional values behind a presence
  byte, lists behind a 4-byte count, instants as epoch seconds and nanoseconds,
  money as currency code and nano-units, and codes as their lower-case names,
  never ordinals. The encoding starts with the version and record type, so two
  different records never encode alike.
- **The verifier rejects any field the encoding does not cover,** and
  duplicate keys, so nothing unhashed can ride along in a line.
- A change to the encoding is a new version. `audit verify` keeps verifying
  every version Tollgate has ever written.

### Tamper evidence

- **Records are sealed in batches.** A batch closes after 1,000 records or one
  second, whichever comes first, and on shutdown.
- **Each batch has a Merkle root** computed as in RFC 6962 (Certificate
  Transparency): leaves are `SHA-256(0x00 ‖ encoding)` and nodes are
  `SHA-256(0x01 ‖ left ‖ right)`, so a leaf can never pass for a node.
- **A seal line closes each batch.** It holds the batch number, the record
  count, the root, the previous seal's root (32 zero bytes for the first), the
  time, and the IDs of the signing key and the HMAC key, so either key can be
  rotated without a new encoding. Its own binary encoding is signed with
  Ed25519, which the JDK provides. Changing, removing, reordering or inserting a
  record, or removing a whole batch, breaks a root, a count, a batch number or a
  signature.
- **The secret keys are references** in `tollgate.yaml`. The signing key is a
  PKCS#8 Ed25519 private key and the HMAC key is at least 32 random bytes, both
  base64. The public key sits in the file as plain text, and Tollgate refuses
  to start unless the signing key belongs to it. A key ID is the first 8 bytes
  of the SHA-256 of the public key or of the HMAC key. Verifiers are given the
  public key separately; a key stored in the trail would prove nothing.
- `java -jar tollgate.jar audit keys create` prints a new signing key, its
  public key and an HMAC key, once.
- **Each run writes its own file** in the audit directory, numbered in order
  (`tollgate-audit-00000001.jsonl`), and never touches an earlier one, so
  finished files can move to write-once storage. A lock file lets only one
  Tollgate write to a directory, so two instances cannot fork the chain. The first seal of a run links to the last seal of the
  previous file. Records a crashed run left after its last seal stay unsealed,
  and `audit verify` reports them. Tollgate does not seal records it did not
  watch being written.

### Writing

- **`AuditSink` writes a list of lines and returns once they are durable.** The
  JSON-lines file sink ships now; Postgres, Kafka and S3 come with the
  production tier.
- **One writer thread and group commit.** Request threads put records on a
  queue. The writer takes everything waiting, appends it, calls
  `FileChannel.force` once, completes those requests' futures, and seals
  batches as they fill. Under load one fsync covers many requests, with no
  added delay when the queue is short.
- **Synchronous mode, the default:** every record is durable before the
  request moves on. The provider is called only after the intent is on disk,
  and a response is returned only after its outcome is, so every response a
  client receives has its cost recorded.
- **Asynchronous mode:** nobody waits. The queue holds at most 10,000 records.
  Records still in the queue when the process dies are lost, and the
  performance report states this trade-off.
- **The audit fails closed.** If the intent cannot be queued, or in
  synchronous mode is not durable within 5 seconds, the request is refused with
  a `503` and code `audit_unavailable`, and its reservation is released. A
  write error stops the writer. From then on every request is refused and the
  readiness probe reports down, so an orchestrator stops sending traffic and
  restarts the instance. If the outcome fails to write after the provider has
  answered, the response is still returned, because withholding it would not
  undo the cost. The error is logged, and `audit verify` reports the request as
  unfinished.
- **The `audit` section is required.** `mode: off` turns the audit off, but
  only when it is written out, so a missing section never means no audit. The
  section is fixed for the life of the process: a config reload that changes it
  is rejected, and the change needs a restart.

  ```yaml
  audit:
    mode: sync                     # sync | async | off
    directory: audit               # relative to tollgate.yaml
    publicKey: MCowBQYDK2VwAyEA…
    signingKey: ${env:TOLLGATE_AUDIT_SIGNING_KEY}
    hmacKey: ${env:TOLLGATE_AUDIT_HMAC_KEY}
  ```

### Verifying

`java -jar tollgate.jar audit verify --public-key MCowBQYDK2VwAyEA… audit` reads
every file in the directory, oldest first, in one pass:

- **Failures** (exit code 1): a line that does not parse, an unknown version or
  field, a root or count that does not match its records, a gap or repeat in
  batch numbers, a broken `previous` link, a bad signature or an unknown key
  ID, an outcome without an earlier intent, or a repeated intent ID.
- **Warnings** (exit code 0): records after a file's last seal, a torn final
  line left by a crash, and allowed intents with no outcome.
- **Each problem is reported with its line number.** The summary ends with the
  last batch's number, time and root, so it can be compared with a copy kept
  elsewhere.

`tollgate-core` holds the records, the encoding, the Merkle tree, sealing, the
writer and the verifier, using only the JDK. `tollgate-server` holds the
JSON-lines sink and the commands, using Jackson.

## Alternatives considered

- **One hash chain over every record.** Simple, but every append waits on the
  previous hash. It cannot prove that one record is in the trail without
  replaying the whole chain, and it needs a signature per record or none at
  all.
- **Hash canonical JSON (RFC 8785).** It needs a canonicalizer in core, which
  has no JSON library, and number formatting is easy to get subtly wrong. A
  binary layout is short to specify and to implement on the JDK.
- **Sign every record.** Bigger trail and more CPU, and it still cannot detect
  a deleted record without a chain.
- **Write outcomes asynchronously in synchronous mode.** Lower latency, but a
  client could hold a response whose cost the trail never recorded.
- **One file, sealing a crashed run's leftovers on restart.** Every batch
  would then be sealed, but the seal would vouch for records that lay unsealed
  while Tollgate was down, and the file could never be made write-once.

## Verification

- **Golden vectors pin the encoding,** so a refactor cannot silently change
  what is hashed. The pinned intent hash was also produced by an independent
  encoder written from this ADR. The seal encoding is checked byte by byte.
- **The Merkle tree passes the RFC 6962 test vectors.**
- **Property tests:**
  - changing any leaf changes the root
  - different records always encode differently
  - any single change to a sealed trail fails verification: an edited byte or
    field, a deleted, duplicated or reordered line, or a removed batch
- **A blocking sink** shows that requests queued during a write share the
  next one.
- **A failing sink** shows that requests are refused and reservations
  released.
- **PIT covers the audit package** like the rest of core.

## Consequences

- **Truncation is not detectable from the file alone.** Removing the last
  batches leaves a trail that verifies. The verify summary makes truncation
  visible to anyone holding an earlier root. Publishing roots outside the
  trail (S3 Object Lock, a transparency log) belongs to the production tier.
- **Anyone holding the signing key can rewrite history,** so the key belongs in
  a secret store, and verifiers need only the public key.
- **The HMACs resist guessing only while the HMAC key stays secret.**
- **Synchronous mode adds a shared fsync before and after each call.** The
  performance report gives figures for each mode.
- **A file grows for as long as its run lasts.** Rotation within a run comes
  with the production tier.
- **The Merkle trees allow proving that one record is in the trail** by
  showing that record and a few hashes, without the rest of the trail. M1
  does not ship that command yet.
