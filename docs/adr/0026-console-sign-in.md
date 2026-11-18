# ADR 0026: Sign in to the console with the admin token

Date: 2026-11-16 · Status: Accepted

## Context

The admin API takes one secret: `TOLLGATE_ADMIN_TOKEN`, sent as a bearer
token. Without it the API answers `503`; with a wrong one, `401`. The README
makes a promise about it: "A web page cannot send it, so a browser cannot be
used to call the admin API behind your back."

The console is a web page that has to send it. How it does so decides
whether that promise survives.

- **Anything the browser sends on its own is a risk.** A cookie or a
  remembered Basic password goes out with every request to the admin port,
  including one that another site's page triggers. That is cross-site
  request forgery, and defending against it needs sessions, tokens and
  origin checks.
- **The quickstart has no identity provider.** `docker run`, open the
  console, paste something. Single sign-on can't be the first step.
- **Some people should look but not touch.** The console will grow buttons
  that change the config (ADR 0029).

## Decision

### Signing in

- **The console asks for the admin token** on its first screen, in a
  password field. It checks it with `GET /admin/v1/info`.
- **The token is kept in the tab's `sessionStorage`** and sent as
  `Authorization: Bearer …` on each API call, by the console's own code.
  Closing the tab forgets it. "Sign out" removes it.
- **There is no cookie and no server session.** The admin API's rule doesn't
  change: no header, no access. A page from another site still can't send
  the token, because it doesn't have it.
- **So there is nothing to forge.** No CSRF token, no origin check and no
  session store are needed, and none is added.
- **A `401` on any call returns to the sign-in screen.** A `503`
  `admin_disabled` shows how to set `TOLLGATE_ADMIN_TOKEN`.
- **The token never appears in a URL,** in `localStorage`, in a log line or
  in an error message.

### Keeping the token from other scripts

A script running in the console's page could read `sessionStorage`. The
defence is that no other script can run there:

- The Content-Security-Policy of ADR 0025 allows only the console's own
  files.
- The console loads nothing from other hosts.
- Values from the API are rendered as text. The code never injects HTML.

### Roles

- **Two static tokens, one optional.** `TOLLGATE_ADMIN_TOKEN` can do
  everything. `TOLLGATE_ADMIN_VIEWER_TOKEN` (`tollgate.admin.viewer-token`)
  may only call `GET` and `HEAD` routes of the admin API; anything else
  answers `403` `admin_forbidden`.
- **The viewer token follows the admin token's rules:** at least 32
  characters, and the two must differ or the server won't start. Without an
  admin token the viewer token alone doesn't switch the API on.
- **`GET /admin/v1/session` says which role the caller has,** so the console
  can hide what a viewer can't do. The server still refuses it.
- **Every read-only operation is a `GET`,** including verifying the audit
  trail (ADR 0028), so the rule stays one line.
- **The viewer token comes after 0.1.0.** Until then the admin token is the
  only way in, and everyone who can look can also change, when edits are
  on. The viewer token sees everything the admin token sees, and may verify
  the trail and look up proofs.

### Later

- **OIDC with viewer, editor and approver roles** is still the plan for the
  managed mode on Postgres (`docs/DESIGN.md` §11). The two tokens are the
  single-instance answer and stay as the break-glass login.

## Alternatives considered

- **A session cookie after signing in** (`HttpOnly`, `SameSite=Strict`).
  Script could no longer read the secret. But the browser would send it by
  itself, so the server needs a session store, expiry, logout, a CSRF token
  and origin checks, and the README's promise becomes "a web page can't,
  unless you are signed in". A script that got into the page could act with
  the cookie anyway.
- **HTTP Basic.** No sign-in screen to build. The browser remembers the
  password until it closes and sends it everywhere on that origin.
- **OIDC now.** Right for teams, and far too much for a first run: an
  identity provider, redirect URLs, token validation, role mapping.
- **No sign-in on loopback.** Convenient until the port is published from a
  container, which the image's defaults do.
- **Keeping the token only in memory.** Safer by a little; every page reload
  would ask for it again.
- **Roles per token in `tollgate.yaml`.** More flexible, but the admin
  credentials would then be reloadable by the admin API they guard.

## Consequences

- **No new way into the admin API.** The filter gains one optional second
  token and a method check, later, and nothing else.
- **The README's sentence about browsers stays true.**
- **Whoever can run script in the console's page has the token.** The
  policy, the no-HTML rule and the short dependency list are what stand in
  the way, so each is tested or reviewed.
- **Everyone with the admin token is the same person** to Tollgate. A change
  made in the console can't say who made it until OIDC.
- **The token is typed again in each new tab.**
