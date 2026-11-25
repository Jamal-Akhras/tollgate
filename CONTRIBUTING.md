# Contributing

Thanks for your interest in Tollgate. Issues and pull requests are welcome.
Everyone taking part is expected to follow the
[code of conduct](CODE_OF_CONDUCT.md).

## Before you start

For anything beyond a small fix, open an issue first so we can agree on the
approach. Changes to extension interfaces, the admin API contract or the audit
format need a short design note in the issue, and lasting decisions get an
[architecture decision record](docs/adr/).

## Building and testing

You need JDK 25. Everything else comes through the Maven Wrapper.

```bash
./mvnw verify          # the build and every check below
./mvnw spotless:apply  # fix formatting
```

CI's main job runs the same checks with `./mvnw -Psbom install`, then tests the
embeddable modules on Java 21 and builds the image. Other jobs run mutation
testing on core, the console in a browser (Playwright), and the Python and
TypeScript libraries.

`verify` fails on:

- formatting differences (palantir-java-format);
- any Error Prone finding or nullness violation (NullAway), and any compiler
  warning;
- test failures or coverage below the module's floor;
- an admin API change without an updated `docs/api/admin-openapi.json`. To
  accept a deliberate change, run
  `./mvnw verify -Dtollgate.updateApiSpec=true` and commit the regenerated
  file with your code.

Core changes should also pass mutation testing:
`./mvnw -pl tollgate-core -Pmutation verify`.

## The console

The console is in `tollgate-console` (TypeScript and Preact, built by Vite).
`./mvnw verify` builds it with a pinned Node, so you don't need Node for that.
To work on it you need Node 24, and its checks take seconds:

```bash
npm --prefix tollgate-console ci --ignore-scripts
npm --prefix tollgate-console run check   # type-check and unit tests
npm --prefix tollgate-console run build   # the files, and the first-load size limit
```

- **TypeScript is strict.** No `any`, no `@ts-ignore`.
- **The console ships three packages:** `preact`, `@preact/signals` and `uplot`.
  A new runtime package needs an amendment to
  [ADR 0025](docs/adr/0025-console-as-a-static-app.md). Build-time packages
  need a reason in the commit.
- **The strict policy is a test** (`src/rules.test.ts`). Never use
  `dangerouslySetInnerHTML`, `innerHTML`, `eval`, `new Function` or a `style="…"`
  string. Colours and sizes come from `styles/tokens.css`. Every request goes
  through `api/client.ts`, and only `state/session.ts` touches the token.
- **Text from the API is rendered as text.**
- **The first load stays under 80 kB gzipped,** or the build fails.
- **Pages are tested against typed fixtures** in `src/api/fixtures`, which have
  the shape of the admin API's answers. A route change updates the contract
  and the fixtures together.

## The client libraries

The Python library is in `clients/python` and the TypeScript one in
`clients/typescript`. The Java one is `tollgate-client`, built by
`./mvnw verify`. `./mvnw verify` doesn't build the other two, so run their
checks yourself ([ADR 0033](docs/adr/0033-publish-the-client-libraries.md)).

For Python you need 3.10 or later. From `clients/python`, in a virtual
environment:

```bash
pip install --require-hashes -r requirements-dev.txt
pip install --no-deps --no-build-isolation -e .
ruff check . && ruff format --check . && mypy && python -m unittest discover -s tests
```

For TypeScript you need Node 22 or later:

```bash
npm --prefix clients/typescript ci --ignore-scripts
npm --prefix clients/typescript run check   # tsc, then the unit tests
```

- **No new dependency** without a reason in the issue. The libraries depend
  only on the vendor SDKs, which are optional.
- **A library decides nothing.** It doesn't retry, log, cache or read a file,
  and reads no environment variable except `TOLLGATE_BASE_URL` and
  `TOLLGATE_API_KEY`. The key never appears in an error, a `repr` or a
  `toString`.
- **Python is typed** (`mypy --strict`), **TypeScript is strict**, and neither
  package has an install script.
- **A dependency change** needs the lockfile or the hashed requirements file
  updated in the same commit.

## Code guidelines

- `tollgate-core` depends only on the JDK and JSpecify; an architecture test
  enforces it.
- Every package is `@NullMarked`. Mark nullable values with `@Nullable` rather
  than suppressing NullAway.
- Prefer immutable records and sealed types. Fail loudly: no silent fallbacks,
  no swallowed exceptions.
- Money is `Money`, never `double`. See
  [ADR 0003](docs/adr/0003-money-representation.md).
- Test behaviour, not implementation. Add property tests where a simpler
  reference implementation exists.

## Commits and pull requests

- One logical change per commit, with a short imperative summary
  (`Add webhook guard timeout`).
- Pull requests describe the problem, the change and how it was tested.
- By contributing, you agree that your contributions are licensed under the
  [Apache License 2.0](LICENSE).
