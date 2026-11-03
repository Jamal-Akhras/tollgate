# ADR 0009: Ship a reviewed price catalog with each release

Date: 2026-11-02 · Status: Accepted

## Context

[ADR 0006](0006-budget-reservations.md) made every model's price required
configuration, with no built-in price list to go out of date. In practice,
that means a new user types four prices for every hosted model before the
first request. Mistyped and stale prices are likely, and setup is slow.

Open-source price databases already exist. [models.dev](https://models.dev)
covers more than a hundred providers. Each model lists input, output,
cache-read and cache-write prices in US dollars per million tokens, the same
four that Tollgate uses.

Prices are a control, not a convenience. They size every reservation and
settle every budget, so whoever controls the price list controls every
budget. Fetching a list at runtime would cause four problems:

- **Integrity:** one bad upstream commit, or one tampered download, could make
  every model free.
- **Audit:** costs could not be traced back to the config version that
  produced them.
- **Availability:** startup would depend on the internet.
- **Accuracy:** the data is an estimate of list prices.

## Decision

### A catalog ships inside each release

- **A snapshot of models.dev** is converted at build time into a plain
  tab-separated file in `tollgate-core`, so the framework-free core can read
  it with the JDK alone.
  - **Each row:** catalog provider, model ID, and the four prices per million
    tokens in USD.
  - **The first line:** the catalog's version, made of the snapshot date and
    the source commit.
- **The catalog is reviewed like code.** A weekly workflow regenerates it and
  opens a pull request. The diff shows every price that changed, and CI runs
  before it can be merged. New prices reach users only when they upgrade.
- **Licence:** the source's licence is confirmed and recorded in `NOTICE`
  before its data is shipped.

### How the config uses it

- **`price` becomes optional.** When it is missing, the model takes the
  catalog's price for its provider and `upstream` name. A `price` that is
  written in always wins, as a whole.
- **Which catalog applies:**
  - **Known hosts:** a provider whose `baseUrl` host is a known one, such as
    `api.openai.com`, `api.anthropic.com` or
    `dashscope-intl.aliyuncs.com`, uses that catalog provider.
  - **Explicit:** `catalog: openrouter` on a provider sets it, and overrides
    the host.
  - **Local and unknown hosts** have no catalog.
- **Nothing is ever free by accident.** A model with no `price` and no catalog
  entry makes the config fail to load. The error names the model and says
  how to set its price. Local models can write `price: free`.
- **Prices are never converted between currencies.** When `currency` is not
  `USD`, catalog prices are not used and every model needs a `price`.
  Converting would need exchange rates, which is another live dependency.
- **Web search** keeps its explicit `price.webSearch`, since the catalog does
  not price it.

### Commands

- **`tollgate prices show <provider> <model>`** prints the price that would
  apply and where it comes from: the config or the catalog, and which
  version.
- **`tollgate prices pin`** prints a `price:` line for every configured model
  that uses the catalog, for pasting into the config. A team can then own and
  edit its prices. Like `keys create`, it prints config and never rewrites
  the file.

### The audit trail stays exact

The config version becomes the SHA-256 of the catalog version followed by
the config file. Every audit record therefore names prices that can be
reproduced. A Tollgate upgrade that changes a catalog price changes the
config version that the next records carry. This amends
[ADR 0007](0007-audit-trail.md).

### Prices that depend on the call

Amended on 2026-11-03. Some models are priced by more than the four rates:

- **Long-context tiers.** About one model in ten charges more once the
  prompt reaches a size, and some have several tiers. For example, Qwen
  models on DashScope change price at 32k, 128k and 256k tokens.
- **Reasoning tokens.** Some providers price reasoning tokens apart from the
  rest of the output, often higher.

A single price per model would charge these wrongly, so `price` describes
them:

```yaml
price:
  input: 0.4
  output: 1.2
  reasoning: 4                 # optional, defaults to output
  cacheRead: 0.08
  cacheWrite: 0.5
  longContext:                 # optional
    - { fromTokens: 256000, input: 1.2, output: 3.6, reasoning: 12, cacheRead: 0.24, cacheWrite: 1.5 }
```

- **A tier applies to the whole call** once its prompt, counting input, cache
  reads and cache writes, reaches `fromTokens`. With several tiers, the
  highest one reached applies.
- **Reasoning tokens** are taken from the reported output tokens and charged
  at the reasoning rate. Providers that do not report them, such as
  Anthropic, charge thinking as output.
- **Reservations stay worst case.** The tier is chosen from the request's
  estimated prompt, which is never below the real one. Output is reserved at
  the higher of the output and reasoning rates.
- **The audit trail records the cost**, as it does for web searches. Its
  record format does not change.

## Alternatives considered

- **Fetching prices at startup or on a schedule.** This is the most current,
  but it hands control of every budget to a third party and a network path,
  and it breaks offline use.
- **Fetching at build time without review.** This is the same trust problem,
  moved to the build, and nobody sees a price change before it ships.
- **Keeping prices manual only.** It is the safest and simplest, but setup is
  slow, and typos turn straight into wrong budgets.
- **Guessing the catalog from the host alone.** That breaks for self-hosted
  proxies and aggregators, so the explicit `catalog` setting stays.
- **Converting catalog prices into other currencies.** Exchange rates would be
  a new live dependency, with the same problems as live prices.
- **Taking the dearest tier and rate for every call.** It never undercharges,
  but ordinary calls on these models would count at two or three times their
  cost, and budgets would run out early.
- **Leaving tiered and reasoning-priced models out of the catalog.** Those
  are many of the Qwen, Gemini and newer GPT models, which people are most
  likely to use.

## Consequences

- **A new setup needs no prices** for models the catalog knows on known hosts.
- **Between upgrades, a provider's price change leaves budgets slightly
  wrong** until an operator writes a `price` or upgrades. The README says
  so.
- **Catalog prices are list prices.** Teams with negotiated rates, batch
  pricing or regional pricing set `price` themselves.
- **The release grows by the catalog**, which is a few hundred kilobytes.
- **ADR 0006 is amended:** prices are still always known before the first
  request, but they can come from the reviewed catalog.
