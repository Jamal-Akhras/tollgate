# Security policy

Tollgate is a security control, so we treat vulnerabilities in it seriously.

## Reporting a vulnerability

Please **do not** open a public issue. Report privately through GitHub, at
https://github.com/Jamal-Akhras/tollgate/security/advisories/new.

Include what you found, how to reproduce it, and the impact you expect. You
will get an acknowledgement within three working days and a status update at
least weekly until the issue is resolved. Once a fix is released, we will
credit you in the advisory unless you prefer otherwise.

## Supported versions

Security fixes are made for the latest minor version, currently 0.1.

## Scope

Reports we especially want:

- bypasses of a guard, data-class rule or port separation;
- secrets or prompt content leaking into logs, traces, errors or audit records;
- tampering with the audit trail that `audit verify` does not detect;
- budget or rate-limit evasion;
- anything that lets a web page, or script in the console, use the admin token
  without the person who holds it, or run in the console despite its
  Content-Security-Policy;
- the client libraries: `tollgate-client` on PyPI and on npm, and
  `io.github.jamal-akhras:tollgate-client` on Maven Central. A library that
  sends a key or a prompt anywhere but the gateway, reads a key it wasn't
  given, or puts a key in an error, a log or a `repr` is in scope, and so is a
  package that doesn't match this repository.

## Checking a package

The PyPI and npm packages are published from this repository's release
workflow, with no stored token. Each has a record of where it was built.

- **npm:** `npm audit signatures` in a project that installed
  `tollgate-client` checks its signature and provenance. The package's page
  on npmjs.com names the workflow and the commit.
- **PyPI:** the file's page on pypi.org shows its attestation, which names this
  repository and the release workflow. The `pypi-attestations` tool checks
  one from the command line.
- **Maven Central:** the artifacts are signed, as are the other Tollgate
  libraries.

If a package doesn't point at this repository, don't install it and report it
as above. The names `tollgate` on PyPI and npm belong to other projects.

## Verifying a download

Each GitHub release has the server jar, `tollgate-<version>.jar`, two SBOMs
and `tollgate-<version>.sigstore.json`, the signed record of how they were
built. With the [GitHub CLI](https://cli.github.com), in the folder you
downloaded them to:

```bash
gh attestation verify tollgate-<version>.jar --owner Jamal-Akhras \
  --bundle tollgate-<version>.sigstore.json
```

It names this repository and the release workflow when the file is the one
that was built there. The same command checks the SBOM files. The container
image has a record too:

```bash
gh attestation verify oci://ghcr.io/jamal-akhras/tollgate:<version> --owner Jamal-Akhras
```
