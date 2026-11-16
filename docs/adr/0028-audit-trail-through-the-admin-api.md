# ADR 0028: Read and prove the audit trail through the admin API

Date: 2026-11-16 · Status: Accepted, amends [ADR 0007](0007-audit-trail.md)

## Context

The audit trail is Tollgate's strongest claim and its least visible one. It
is a folder of JSON lines, and the only way to see that it holds is to run
`audit verify` and read "The audit trail is intact."

ADR 0007 ends with a promise it didn't keep: "The Merkle trees allow proving
that one record is in the trail by showing that record and a few hashes,
without the rest of the trail. M1 does not ship that command yet."

The console should show the structure: batches chained by their roots, a
batch's tree, and the proof that one call's record is in it. And it should
let the reader check that proof themselves, not take the server's word.

Two limits shape the API:

- **A trail can be large.** A file grows for as long as its run lasts, and
  nothing indexes it. The usage report already reads every file on each
  request (ADR 0017).
- **Reading it must not hurt the gateway.**

## Decision

### Inclusion proofs, in core

- **`tollgate-core` gains the audit path of RFC 6962:** for a batch's
  records and one position, the sibling hashes from that leaf up to the
  root.
- **A proof holds** the record's binary encoding, its leaf hash, its
  position, the batch's size, the path and the root. Anyone can check it
  with SHA-256 alone: hash `0x00 ‖ encoding`, combine with each sibling
  under `0x01`, and compare with the root in the seal.
- **It is tested like the tree is:** paths over the RFC 6962 test leaves
  lead to the roots the tree's tests already pin, and a property shows that
  every position in every tree size up to 300 proves, and that a changed
  leaf, path or position doesn't.

### Endpoints

All are `GET`, on the admin port, behind the admin token.

| Endpoint | Returns |
|---|---|
| `/admin/v1/audit` | The mode, the public key and its ID, the number of files, and the last seal |
| `/admin/v1/audit/batches?limit=50` | The most recent sealed batches, newest first: number, record count, time, root, previous root, key IDs, file |
| `/admin/v1/audit/batches/{batch}` | That batch's seal, whether its signature and root check out, and its leaves in order: type, request ID, time, leaf hash |
| `/admin/v1/audit/records/{id}` | The request's intent and outcome, each with its inclusion proof and its batch's seal, or `sealed: false` while its batch is still open |
| `/admin/v1/audit/verification` | The result of verifying the whole trail: intact or not, counts, failures and warnings with file and line, and the last seal |

- **Records are returned in the trail's own JSON form** (ADR 0007), so there
  is one description of a record, not two.
- **With the audit trail off,** each answers `409` `audit_off`.

### What reading costs, and its bounds

- **The list of recent batches reads only the end of the trail,** at most
  8 MB, newest file first. It shows what was sealed lately, not the whole
  history.
- **Finding one batch or one request looks there first,** then reads the
  files from newest to oldest until it finds it. An old record costs a pass
  over the trail, like the usage report.
- **Verification reads everything** and checks every signature.
- **One of these long reads runs at a time.** A second one answers `429`
  `audit_busy` with `Retry-After`. The live reader of ADR 0027 is separate
  and not affected.
- **The reads use ordinary file I/O on a virtual thread.** They take no lock
  the writer takes.

### What the console does with them

- **Batches** are drawn as a chain: each card shows its root and points at
  the previous one.
- **A batch** is drawn as its tree. The browser computes the inner nodes
  from the leaf hashes with WebCrypto. Large trees show the top levels and
  the path being proved.
- **A record's proof is checked in the browser:** it hashes the encoding,
  walks the path and compares the result with the seal's root, step by
  step on screen. The seal's signature is checked by the server and shown
  as its result.
- **Without WebCrypto** (a plain-HTTP console on another machine) the
  console shows the server's result and says the browser couldn't check.
- **Verifying the whole trail** is a button, with the failures and warnings
  listed as `audit verify` prints them.

### Trust

- **The server verifies with the public key in its own config.** That
  proves the trail matches that key. It doesn't prove the key is the one
  you were given: someone who can change the config can change the key.
- **So the console shows the key and its ID** next to every result, and
  says to compare it with a copy kept elsewhere. `audit verify` with a key
  you supply remains the independent check.
- **Truncation is still not detectable from the trail alone** (ADR 0007).
  The console shows the last batch's number and root so they can be noted
  down.

### Not in this decision

- **A command, `audit prove <id>`.** The core code makes it a few lines; it
  comes after the console.
- **An index of the trail.** The Postgres tier answers these requests from
  queries.
- **Checking the seal's Ed25519 signature in the browser.**

## Alternatives considered

- **Server-side verification only.** Less to build. The page would then say
  "verified" because the server said so, which is the thing the Merkle tree
  exists to avoid.
- **Sending a batch's whole tree.** The browser needn't hash. A thousand
  leaves is two thousand hashes on the wire, and the picture would again
  be the server's word.
- **An index file next to the trail,** mapping batches and request IDs to
  positions. Fast lookups, and one more file to keep consistent with a
  trail whose files are meant to be write-once.
- **An index in memory.** No file, but it grows with the trail and is
  rebuilt by a full read at each start.
- **Only the recent window, no lookups beyond it.** Bounded and simple, but
  "prove any call" would be "prove a recent call".
- **`POST` for verification,** as an action. It changes nothing, and `GET`
  keeps "viewers may only read" a rule about methods (ADR 0026).
- **A typed JSON shape for records in the API,** generated into the OpenAPI
  document. Better tooling, and a second schema to keep in step with the
  audit format.

## Consequences

- **The trail's structure can be seen and checked** by someone who has
  never run `audit verify`, and one call can be proved without the rest.
- **ADR 0007's deferred promise is kept** for the API; the command follows.
- **Core gains public API** for proofs, covered by PIT like the rest.
- **Looking up an old record reads the trail,** and gets slower as it
  grows, until the Postgres tier. Only one such read runs at a time.
- **Verification from the console trusts the configured key.** The page
  says so.
- **The admin API now serves audit records.** They hold no content, but
  they name tenants, keys, models and refusal messages, as the files do.
  The admin token guards both.
