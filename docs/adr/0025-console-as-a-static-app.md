# ADR 0025: The console is a static app on the admin port

Date: 2026-11-16 · Status: Accepted, amends
[ADR 0004](0004-studio-and-admin-api.md)

## Context

ADR 0004 decided two things. The admin API is the product, with a published
OpenAPI contract. And "Studio", the UI, would be rendered on the server with
JTE and htmx, with no npm toolchain. Only the first was built.

The UI now has a design: a flow page in the style of NetBird's Control
Center, live numbers without Grafana, and a view of the audit trail as Merkle
trees. That changes what the UI is:

- **Most of its state lives in the browser.** Which tenant is selected, which
  rule is open, the layout at each width, the preview switch. Server
  rendering would need hand-written JavaScript for all of it, with no way to
  type-check or unit-test that code.
- **Its data is JSON whatever renders it.** Charts need numbers, and the later
  Connect app needs the same endpoints.
- **Rendering on the server runs inside the gateway's JVM.** The console must
  not cost the gateway anything a request could feel.

ADR 0004 rejected React for its toolchain and its hundreds of packages. That
objection stands. A small compiled app does not have to bring them.

## Decision

### What it is

- **It is called the console.** "Studio" is retired as a name.
- **A single-page app of static files.** TypeScript and Preact, with
  `@preact/signals` for state, built by Vite. The server sends the files and
  never renders a page.
- **It talks only to the admin API.** Every number on screen comes from an
  endpoint in `docs/api/admin-openapi.json`. The console has no private
  endpoints.
- **It ships three runtime packages:** `preact`, `@preact/signals` and
  `uplot`. uPlot draws time series and loads only on the Metrics page. The
  flow diagram and the Merkle tree are the console's own SVG.
- **No Tailwind, no component library, no icon package, no router package.**
  Styles are hand-written CSS with custom properties and CSS Modules. Routes
  are hash routes (`/console/#/flow`), so the server needs no fallback route.
- **Nothing is fetched at runtime** from anywhere but the admin port. No CDN,
  no web fonts from another host, no analytics.

### Where it lives and how it is built

- **A new module, `tollgate-console`,** holds the app's source and builds a
  jar with the files under `META-INF/tollgate-console/`. Only
  `tollgate-server` depends on it. The starter doesn't: an embedded gateway
  has no admin API and no console.
- **`mvnw verify` still builds everything.** `frontend-maven-plugin` installs
  a pinned Node LTS into the module, runs `npm ci --ignore-scripts` against
  the committed lockfile, type-checks, runs the unit tests and builds.
  Nobody installs Node by hand.
- **Browser tests run in CI,** not in `mvnw verify`: Playwright drives the
  built console against a stub of the admin API, and fails on any
  Content-Security-Policy violation.
- **A size budget is a test.** The first load (HTML, CSS and JavaScript,
  without uPlot) must stay under 80 kB gzipped. The build fails above it.
- **A new runtime package needs an amendment to this ADR.** Build-time
  packages need a reason in the commit that adds them.
- **Dependabot and CodeQL cover npm and JavaScript,** and the SBOM lists the
  three shipped packages.

### How it is served

- **At `/console/` on the admin port only.** `/` on the admin port redirects
  there. On the gateway port both are a 404, like every admin route.
- **The files need no token.** They hold no data. Every API call the console
  makes needs the admin token (ADR 0026).
- **Not under `/admin`,** because that path is the API's and its filter asks
  for the token before anything else.
- **Headers on every console response:**
  - `Content-Security-Policy: default-src 'none'; script-src 'self';
    style-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self';
    base-uri 'none'; form-action 'none'; frame-ancestors 'none'`
  - `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
    `Cross-Origin-Opener-Policy: same-origin`
  - `Cache-Control: no-cache` for `index.html`; a year and `immutable` for
    files named by their content hash.
- **No inline script and no inline style, ever.** The code sets styles
  through classes or the CSSOM, never a `style` attribute in markup, and
  never injects HTML.
- **`tollgate.console.enabled=false`** (`TOLLGATE_CONSOLE_ENABLED`) removes
  the console: its files answer 404 and nothing it needs runs. The admin API
  stays.

### What stays from ADR 0004

- The admin API is the product, and its contract changes only through a
  reviewed commit.
- The console is one client of it.
- A strict Content-Security-Policy.
- Admin API, console and OpenAPI document on the admin port only.

### What this replaces in ADR 0004

- "Rendered on the server with JTE and htmx."
- "There is no npm toolchain and no bundler."
- "One build covers the whole product, and Java contributors can change the
  UI." The first half stays true. The second doesn't.

## Alternatives considered

- **JTE and htmx, as ADR 0004 said.** No Node, and the fewest dependencies.
  But the flow page becomes untyped, untested DOM scripting, the server
  grows a second surface of HTML fragments outside the contract, every open
  console makes the gateway render templates, and htmx is between two
  incompatible major versions.
- **The same with Alpine or Lit for the flow page.** Alpine's CSP build
  drops much of its expression syntax and is the largest file in that stack.
  Lit adds a second way to render and still has no build to check it.
- **Svelte 5.** As small, and a good fit. It was not chosen because it adds
  a compiler and about 35 build packages, fewer people can read it, and
  Preact can run React Flow later through its compatibility layer if the
  diagram outgrows hand-drawn SVG.
- **React with React Flow, Radix, Tailwind and Chart.js,** which is what
  NetBird's dashboard uses. The shortest road to the same pixels. Five times
  the bytes, inline styles that fight a strict policy, and a lockfile of
  many hundreds of packages in a security product.
- **No build step: Preact and `htm` as vendored ES modules.** No Node at
  all. But nothing type-checks or unit-tests the code, so every check needs
  a browser, and the agents that write the code lose their narrow checks.
- **Committing the built files.** No Node in the build, but a minified blob
  nobody can review.
- **Putting the app inside `tollgate-server`.** One module fewer, but every
  server test run would wait for Node.

## Consequences

- **The gateway serves files and answers JSON.** The console's rendering
  costs it nothing.
- **Node and a lockfile join the build.** A cold build downloads Node and
  the build packages, and takes longer. A reviewer reads a lockfile as well
  as a POM. What ships stays three packages.
- **The UI needs TypeScript to change,** and JavaScript to show anything.
- **The look is matched by hand.** There is no pan, zoom or drag on the
  diagram until someone needs it.
- **Other UIs lose nothing.** The contract is still the product, and the
  console proves it is enough to build on.
- **`docs/DESIGN.md` §2 and §11 and the roadmap change:** `tollgate-studio`
  becomes `tollgate-console`, and it moves from "Later" to 0.2.0.
