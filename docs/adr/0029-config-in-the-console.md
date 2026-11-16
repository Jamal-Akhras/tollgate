# ADR 0029: The console shows the config, and changes it only by rewriting the file

Date: 2026-11-16 · Status: Accepted, amended 2026-11-16, amends [ADR 0004](0004-studio-and-admin-api.md)

## Amendment (2026-11-16)

The first version of this ADR shipped 0.2.0 read-only and left managed mode
for later. The control surface was then settled, and four edits moved into 0.2.0.
What changed:

- **Managed mode ships in 0.2.0,** with exactly four edits: a guard's mode,
  a tenant's budget, create a key, revoke a key. Every other edit is still
  later.
- **Managed mode is still off by default.** `tollgate init` now turns it on
  for the quickstart, by writing `TOLLGATE_ADMIN_EDITS=file` into
  `tollgate.env`.
- **The four routes, their errors and the write's steps are spelled out,**
  including what "every version that ran is kept" takes.
- **Losing the file's comments on the first managed write is accepted.**
- **The Docker quickstart mounts the config's folder, not the file,** and
  not read-only. A single mounted file can't be replaced atomically.
- **The viewer token stays after 0.2.0.** ADR 0026 said it arrives with the
  first endpoint that changes the config; that sentence needs the same
  amendment.
- **A reload sums the trail again for a tenant whose budget period changes,**
  or that gets its first budget, so the period change no longer zeroes the
  count until a restart.
- **"Pause a tenant" is not one of the four.** It is a new setting and has
  its own ADR (0030).
- **The write is shaped so a later store can reuse it:** edits as
  operations on the document, a small store behind the writer, a pinned
  layout, one logged value per write. No behaviour or setting comes with
  this.

Everything else stands as first accepted.

## Context

`tollgate.yaml` is the source of truth. The server, the starter and the
commands read the same file the same way, a reload validates it and swaps it
in atomically, and every audit record names the SHA-256 of the config that
decided it (`docs/DESIGN.md` §4).

The console has to show that config: tenants, keys, policies and their
guards, providers, models. The flow page is drawn from it. And the console
is meant to change it: switch a guard between enforcing and monitoring, add
a key, set a budget. A console that can only report is a dashboard; the
four edits below are what make it a control panel.

`docs/DESIGN.md` §11 sketched two modes. In **GitOps** mode the file lives in
Git and the UI is read-only. In **managed** mode the config is versioned in
Postgres with approvals. Postgres is not built. Between the two sits the
common case: one instance, one file, one operator who would like a switch.

Four facts limit what a write can be:

- **The file is often not writable.** The 0.1.0 Docker quickstart mounts it
  read-only, and the systemd example keeps it in `/etc/tollgate`, which the
  service user can't write.
- **The file holds no secrets, only references,** and tenant keys only as
  hashes (`keys: - { id, tenant, sha256 }`). It is safe to show to an
  administrator. It does hold detector terms, such as client names.
- **Rewriting YAML through a parser loses its comments and layout.**
- **A reload is already a transaction.** `Gateway.reload()` reads the file,
  validates it, refuses a changed `audit` section, resolves the provider
  keys, and only then swaps the running config. It holds one lock while it
  does. A write can use the same steps under the same lock.

## Decision

### Reading

- **`GET /admin/v1/config`** returns the running config as JSON: its
  version, data classes, providers, models, tenants, key IDs with their
  tenants, policies with each guard's type, mode and settings, and the
  audit mode and public key.
- **It is a view, not the file.** Secret references are shown as their
  variable names. Key hashes, detector patterns and detector terms are left
  out.
- **It says which mode edits are in:** `read-only` or `managed`. In managed
  mode it also says whether the file has been rewritten yet, so the console
  can warn before the first write.

### Read-only is the default

- **The console changes nothing.** It shows the config and has a Reload
  button, which calls the existing `POST /admin/v1/config/reload` and shows
  a rejected file's message as it is.
- **Where a change would go, it shows the YAML to write,** as `keys create`
  does on the command line: the line to change, then "Reload".
- **This is the GitOps mode of `docs/DESIGN.md` §11.** A team that keeps the
  file in Git leaves edits off and loses nothing.

### Managed mode, in 0.2.0

Set `tollgate.admin.edits=file` (`TOLLGATE_ADMIN_EDITS=file`) and the admin
API may rewrite `tollgate.yaml`. The default is `off`. The file is still the
only source of truth; there is no second file and no database.

- **`tollgate init` turns it on,** with a line in `tollgate.env`. Someone
  trying Tollgate gets the switches. Someone deploying it removes the line
  or leaves the file read-only on purpose.
- **Each change is its own endpoint.** 0.2.0 has four:

  | Route | Changes |
  |---|---|
  | `PUT /admin/v1/policies/{policy}/guards/{index}/mode` | a guard's `mode`: `enforce` or `monitor` |
  | `PUT /admin/v1/tenants/{tenant}/budget` | a tenant's `budget`: `limit` and `period` |
  | `POST /admin/v1/tenants/{tenant}/keys` | adds a key; answers with the key, once |
  | `DELETE /admin/v1/keys/{id}` | removes a key |

- **A write is: read the file, change the document, validate it exactly as
  a reload does, write it next to the original, move it into place
  atomically, swap the running config.** The validation and the swap are
  the reload's own code, under the reload's lock. If any step fails, the
  running config and the file are as they were.
- **It needs `If-Match` with the running config version.** Without the
  header the answer is `428`. If the version isn't the one running, or the
  file on disk is no longer the one running (changed by hand and not yet
  reloaded), the answer is `412` and nothing is written.
- **The file is rewritten in a fixed layout, and comments are not kept.**
  The first managed write saves the hand-written file beside it, untouched,
  as `tollgate.yaml.original`. The user accepted this cost.
- **The rewrite must read back as the same document.** Parsing the new file
  gives the changed document, key for key and value for value. This is
  tested as a property over every example config, because a wrong rewrite
  of this file is worse than a lost comment.
- **Every version that ran is kept** in `config-history/<version>.yaml`
  beside the file, so the version in an audit record can be looked up. In
  managed mode that means the version running at startup, each version a
  reload brings in, and each version a write replaces or creates. Nothing
  deletes them.
- **Every write is logged from one small value:** an ID for the change,
  who asked (in 0.2.0 always the admin token), the versions before and
  after, and each operation's name and the place in the document it
  touched. A new key's secret is never part of it. The same value is what
  a later release would store or put in the audit trail.
- **Each edit is an operation on the config document:** a name, the place
  it touches, and a change that takes the document and returns it
  changed. It knows nothing of its endpoint or of how the file is
  written, so the same operation can run against another store.
- **The file is written through a small store:** read what is stored now,
  replace it if it is still the expected version, keep a version. Looking
  one up and listing them can be added when something needs them. The file
  is the only store. This is structure, not
  a setting; nothing chooses a store.
- **The fixed layout is deterministic and pinned by a test,** because the
  layout decides the config version.
- **A change that changes nothing writes nothing.** Setting a guard to the
  mode it already has answers with the running version.
- **The `audit` section and the server's own settings can't be changed**
  this way. They need a restart (ADR 0007) or belong to the environment.
- **Only the admin token may write.** The viewer token can't (ADR 0026).
- **With the mode off, write endpoints answer `409` `edits_off`.**
- **With the mode on and the file or its folder not writable, the server
  doesn't start,** and says which path it couldn't write and that removing
  `TOLLGATE_ADMIN_EDITS` is the other way out. Asking for edits that can't
  happen is a configuration error, like a short admin token.

### The four edits

- **A guard's mode.** The guard is named by its policy and its position
  under `guards:`. `monitor` writes `mode: monitor`; `enforce` removes the
  line, which is how the file says it by hand.
- **A tenant's budget.** A limit as a decimal and a period (`daily`,
  `monthly`, `total`), in the config's currency. It sets a budget on a
  tenant that had none. Removing a budget is not in 0.2.0.
  - What is already spent stays counted when only the limit changes. A
    limit below what is spent refuses the tenant's priced calls at once.
  - Changing the period, or setting a first budget, makes the reload sum
    the audit trail again for that tenant, so the count for the new period
    is rebuilt from the trail, as a restart does. A first budget counts only
    the calls that carried a reservation, so it starts from now in effect.
    With the audit trail off the count starts from zero and a warning is
    logged. The console says so before it writes.
  - The sum runs while the tenant's ledger is locked, so no call sees a
    count of zero. Calls in flight during it can only add to the count:
    a call reserved under the old period settles into the new period, and
    a call that is open in the trail is counted at its full reservation
    and again at its real cost when it settles. The count can be high by
    the calls in flight at that moment and is never low.
- **Create a key.** The server makes the key as `keys create` does and
  writes only `{ id, tenant, sha256 }`. The answer carries the key once,
  with `Cache-Control: no-store`. It is not logged, not kept and can't be
  asked for again.
- **Revoke a key.** The entry is removed and the reload that follows stops
  accepting it. Calls already running finish. Revoking a tenant's last key
  is allowed; it is how a tenant is stopped today (ADR 0015).

### What "off" means for a guard

- **The console's switch on a guard moves it between `enforce` and
  `monitor`** (ADR 0022). A monitored guard still runs and still records
  what it would have refused, so switching a rule off never switches off
  the evidence.
- **Removing a guard is an edit to the list,** not the switch, and comes
  later.
- **Switching a `data-class` guard to monitor asks first,** with the
  warning ADR 0022 logs: data can then reach regions the guard would
  refuse.
- **The built-in request review, budgets and rate limits have no switch.**
  They always enforce (ADR 0022).
- **The "On" switch in the console's top bar changes nothing.** It redraws
  the flow page as it would be without Tollgate: no rules, direct lines to
  providers, regions that become reachable, nothing recorded. It is a
  preview and is labelled as one. The switch that really turns Tollgate off
  for a machine belongs to the Connect app.

### What the console asks before it writes

- **Every edit shows the lines of YAML that change,** before and after, and
  writes only when the operator confirms.
- **The first write says the file will be rewritten** and where the
  original is kept.
- **A change that widens what can happen asks in words:** monitoring a
  `data-class` guard, lowering a budget below what is spent, revoking a
  tenant's last key.
- **These questions are the console's.** The API does what an administrator
  with the token tells it; `curl` gets no second prompt.

### Not in this decision

- **A record of config changes in the audit trail.** It needs a new record
  type and encoding. It comes with the managed mode on Postgres, where
  changes also get an author. Until then the evidence is the log line, the
  history folder, and the config version on every audit record.
- **Approvals and rollback** (`docs/DESIGN.md` §11). The history folder
  holds what a rollback would need; nothing reads it yet.
- **Every other edit:** guard settings, rate limits,
  tenants, policies, providers, models, removing a budget, rotating a key.
  Each is a later endpoint under the rules above.
- **Pausing a tenant** (ADR 0030).
- **The viewer token** (ADR 0026). It comes after 0.2.0, sees everything
  the admin token sees, and may verify the trail and look up proofs. Those
  are all `GET`s, so its one rule, "only `GET` and `HEAD`", already says
  it.

## Alternatives considered

- **A second file for the console's changes,** merged over the first. The
  hand-written file keeps its comments. But there would be two places to
  look for a rule, the merge needs its own rules, and the config version
  would cover two files.
- **Editing the YAML as text in the browser,** with a validate endpoint.
  Comments survive and the operator sees exactly what changes. A switch
  would then be a text patch made in JavaScript, which is the fragile part.
- **A parser that keeps comments.** The right tool if one existed for the
  JVM that could be trusted with a security config. A wrong rewrite of this
  file is worse than a lost comment.
- **Keeping changes in memory until restart.** Nothing to write, and the
  running config would no longer match its file or its version.
- **One generic endpoint taking a list of changes.** One route to
  document, and a small language to design, validate and keep compatible.
- **Putting the whole config in a database now.** It is where managed mode
  ends up, and it is a storage tier Tollgate doesn't have yet.
- **Shipping 0.2.0 read-only,** as first decided. Less to build and nothing
  to get wrong in a write. But the console would show a switch-shaped thing
  that opens instructions, and the four edits are what people ask a
  console for first.
- **Edits on by default.** One step fewer. A release that starts rewriting
  a file it only read before is not something to do to a team unasked, and
  most deployed files are read-only anyway.
- **Starting anyway when the file isn't writable,** with a warning and
  `409` on each write. The gateway would stay up. But the operator asked
  for edits and would find out they don't work from a failed click.
- **Editing the mounted file in place for Docker,** so the single-file
  mount could stay. A crash halfway leaves half a config.
- **A confirm flag on the API** for the changes that widen access. The
  console's question would then be enforced for scripts too. A script that
  must send `confirm=true` just sends it.

## Consequences

- **In 0.2.0 the console can switch a guard, set a budget, and create and
  revoke keys,** when edits are on. Everything else is still made in the
  file, and the console shows the YAML to write.
- **Managed mode costs a team its comments,** once, in exchange for
  switches. The original is kept beside the file. A team that keeps the
  file in Git stays read-only and loses nothing.
- **The quickstart changes.** `init` writes one more line into
  `tollgate.env`. The Docker quickstart mounts the working folder instead
  of one read-only file, so the config can be replaced and the history
  kept. The systemd example says to drop the line, or to keep the config
  somewhere the service can write.
- **An instance started from `init`'s files with a read-only config stops
  at startup** until the line is removed. The message says so.
- **The config version stays the hash of one file,** and audit records keep
  pointing at it. The history folder now answers "what was that version".
- **The history folder grows by one small file per change** and is never
  pruned.
- **The rewritten file loses its owner's layout and, where the filesystem
  has them, takes the server's default permissions.** It holds no secrets.
- **Changes made in the console have no author** until sign-in has
  identities. With one token, everyone who can look can also change, until
  the viewer token arrives.
- **The admin API shows names from the config** to anyone with the admin
  token: tenants, models, providers, environment variable names.
- **The admin API can now mint tenant keys.** Whoever holds the admin token
  could already do it by editing the file; now a stolen token is enough
  without access to the disk. The admin port stays on loopback by default.
- **`Gateway` gains a write path beside `reload()`.** It is not on the
  request path, and requests still only read a snapshot.
