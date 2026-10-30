# Contributing

Thanks for your interest in Tollgate. Issues and pull requests are welcome.

## Before you start

For anything beyond a small fix, open an issue first so we can agree on the
approach. Changes to extension interfaces, the admin API contract or the audit
format need a short design note in the issue, and lasting decisions get an
[architecture decision record](docs/adr/).

## Building and testing

You need JDK 25. Everything else comes through the Maven Wrapper.

```bash
./mvnw verify          # what CI runs
./mvnw spotless:apply  # fix formatting
```

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
