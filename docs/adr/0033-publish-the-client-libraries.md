# ADR 0033: Build, test and publish the client libraries from this repo

Date: 2026-11-17 · Status: Accepted, amends
[ADR 0001](0001-runtime-and-build.md)

## Context

ADR 0031 adds client libraries in Java, Python and TypeScript. Tollgate has
published to Maven Central, GitHub's container registry and GitHub releases.
It has never published to PyPI or npm.

A package that sits between an application and its model provider is a place
an attacker wants to be. A stolen publish token, a typosquatted name or a
build script from a dependency would put someone else's code where the
tenant key and the prompts are. So how these packages are built and released
matters as much as what is in them.

## Decision

### Where the code lives

- **In this repo,** so a change to the gateway's headers and the libraries
  that read them is one commit:

  | Path | What | Published as |
  |---|---|---|
  | `tollgate-client/` | Java, a Maven module like the others | `io.github.jamal-akhras:tollgate-client` on Maven Central |
  | `clients/python/` | Python | `tollgate-client` on PyPI, imported as `tollgate` |
  | `clients/typescript/` | TypeScript | `tollgate-client` on npm, with `tollgate-client/openai` and `tollgate-client/anthropic` |
  | `clients/e2e/` | A stub provider, a test config and a script that starts the server jar | not published |

- **The name `tollgate` is taken** on both registries by other projects, so
  both get `tollgate-client`. The Python import stays `tollgate`.
- **`mvnw verify` doesn't build the Python or TypeScript library.** The
  server doesn't contain them, and the Maven build should not need Python.
  CI builds them in their own job.

### One version

- **Every library has the gateway's version** and is released by the same
  tag. `pyproject.toml` and `package.json` hold the next release's number,
  and the release stops if either differs from the tag.
- **A library works with a gateway of its own minor version or a later
  one.** Until 1.0, nothing more is promised.
- **Each library names the vendor SDK versions it was tested with:** from the
  tested version up to, not including, the next major.

### What a package may contain

- **No dependency of its own.** The Python and TypeScript packages depend
  only on the vendor SDKs, as optional extras and optional peers. The Java
  module depends only on JSpecify, with the vendor SDKs optional.
- **No install script, no network call at import, no telemetry.**
- **Only the listed source files.** Each package names its files, and a test
  compares the built archive's file list with that list.
- **It reads two environment variables,** `TOLLGATE_BASE_URL` and
  `TOLLGATE_API_KEY`, and never prints the key.

### How they are tested

- **Unit tests in each language** run the real vendor SDK against a small
  HTTP stub from the language's standard library. They check the address,
  the key, the headers sent, and the receipt and refusal read back.
- **End-to-end tests** run the real vendor SDK, through the library, against
  the built server jar, with a stub provider behind it: a chat, a message, a
  stream, a refused tool, a dry run and the model lists. One script starts
  the stub and the server; Python and TypeScript each have a handful of
  tests it runs.
- **The Java library's end-to-end test is in the Maven build.** It starts the
  gateway from the starter inside a small Spring Boot application, the way a
  Java user would, with the same stub provider the other modules use. It
  also shows the vendor SDKs' Jackson 2 and Tollgate's Jackson 3 working on
  one classpath.
- **Both run in CI on every push,** in a `clients` job. This is the test
  that finds a vendor SDK release the gateway's request review refuses.
- **The recorded shapes of ADR 0020 stay as they are.** They prove the
  gateway accepts what a client sends; these tests run the client.

### How they are published

- **Maven Central as today:** uploaded by the release workflow, published by
  hand.
- **PyPI by trusted publishing:** the release workflow gets a short-lived
  token from GitHub's OIDC. No PyPI token is stored anywhere. The PyPA action
  attaches attestations.
- **npm by trusted publishing,** the same way, with provenance.
- **Each registry is its own job** in the release workflow, with only
  `id-token: write`, in a GitHub environment that needs the owner's
  approval. It runs after the build and the Central upload have passed, so a
  publish that can't be undone is always a decision.
- **After the first release,** the npm package is set to refuse tokens.
- **The first npm publish is by hand:** a `0.0.0` placeholder that claims the
  name, since npm has no trusted publisher for a package that doesn't exist.
  PyPI uses a pending publisher instead.

### Tools

- **Python:** `pip` and a committed `requirements-dev.txt` in which every
  package has a version and a hash, installed with `--require-hashes`.
  `hatchling` builds, and the editable install in CI too, without build
  isolation, so every build uses the pinned copy. `unittest` to test, `ruff` and `mypy --strict` to check. Python 3.10
  or later, as the vendor SDKs need.
- **TypeScript:** `tsc` only, strict, ES modules, Node 22 or later. Tests run
  on Node's own test runner. A committed lockfile and
  `npm ci --ignore-scripts`, as for the console.
- **Java:** the existing build. The Java library compiles for Java 17, so
  it reaches applications that can't run the starter's Java 21.
- **Dependabot** covers both new folders, **CodeQL** adds Python, and every
  new action is pinned to a commit.

## Alternatives considered

- **A repo per language.** Usual for big SDKs. Here each library is one small
  module that follows the gateway's headers, and three more repos
  means three more sets of secrets, settings and release steps.
- **Publish tokens in GitHub secrets.** The classic way, and the thing
  attackers look for. Trusted publishing leaves nothing to steal.
- **Publishing on the tag with no approval.** One step less. PyPI never lets
  a version number be used twice, so a bad upload is permanent.
- **Their own version numbers.** A library could then be fixed without a
  gateway release. It also makes "which library works with which gateway" a
  table someone has to keep. Revisit if the libraries start changing on
  their own.
- **`uv` and its lockfile for Python.** Faster and tidier. It is one more
  tool to install and one more third-party action in the release workflow,
  for a package with five development dependencies.
- **Generating the libraries.** There is nothing to generate: they have no
  request types of their own (ADR 0031).
- **Building them in `mvnw verify`.** One command for everything, but every
  contributor's Maven build would download Python tooling.
- **`pytest`, Vitest, a bundler, a formatter for TypeScript.** Each is more
  packages in a lockfile for code this small.
- **Java 21 for the Java library, like the others.** One baseline for every
  module. But the library is the one part a Java 17 application could use, and
  it is set per module in Maven.

## Consequences

- **One tag releases the server, three Java libraries, a Java client, a
  Python package and an npm package.** The release has more ways to fail
  halfway, and the two new jobs can be run again on their own.
- **The owner approves two more steps per release,** and needs accounts with
  two-factor sign-in on PyPI and npm.
- **No publish secret exists** for PyPI or npm.
- **Users can check where a package was built:** PyPI attestations and npm
  provenance point at this repo's release workflow.
- **CI needs Python and Node besides Java,** and takes a few minutes longer.
- **A vendor SDK's new major version** needs a change here before
  applications can use it with the library. They can always drop the library
  and set the base URL themselves.
