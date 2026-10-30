# Security policy

Tollgate is a security control, so we treat vulnerabilities in it seriously.

## Reporting a vulnerability

Please **do not** open a public issue. Report privately through GitHub:
**Security → Report a vulnerability** on this repository.

Include what you found, how to reproduce it, and the impact you expect. You
will get an acknowledgement within three working days and a status update at
least weekly until the issue is resolved. Once a fix is released, we will
credit you in the advisory unless you prefer otherwise.

## Supported versions

Tollgate has not had a release yet. Once it does, security fixes will be made
for the latest minor version.

## Scope

Reports we especially want:

- bypasses of a guard, data-class rule or port separation;
- secrets or prompt content leaking into logs, traces, errors or audit records;
- tampering with the audit trail that `audit verify` does not detect;
- budget or rate-limit evasion.
