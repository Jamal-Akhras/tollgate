# Client libraries

Libraries for calling a Tollgate gateway from your own code.

- `python` is the Python library. Install it from PyPI as `tollgate-client`,
  and import it as `tollgate`.
- `typescript` is the TypeScript library. Install it from npm as
  `tollgate-client`.
- `e2e` holds the harness that runs both libraries against a real gateway in
  CI (`serve.py` starts the server; each library keeps its own end-to-end
  tests next to its code).

All three libraries are named `tollgate-client`: the two here, and the Java
library, which is the `tollgate-client` Maven module at the root of this
repository.

Each library wraps the vendor SDK you already use (OpenAI or Anthropic) and
points it at the gateway. See the README in each folder for how to use it, and
[CONTRIBUTING.md](../CONTRIBUTING.md) for how to run the checks.
