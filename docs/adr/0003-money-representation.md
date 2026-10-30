# ADR 0003: Money as integer nano-units

Date: 2026-10-29 · Status: Accepted

## Context

Budgets and the cost ledger must be exact and auditable. Token prices are tiny:
a model at $0.15 per million tokens charges $0.00000015 per token, so cent-based
integers cannot represent per-request costs. `double` cannot represent them
exactly either. `BigDecimal` is exact, but its `equals` depends on scale (`1.0`
is not equal to `1.00`), a common source of ledger bugs.

## Decision

- `Money` is a record holding a `long` count of **nano-units** (10⁻⁹) and a
  `Currency`. The range is about ±9.2 billion major units, far beyond any
  per-request or per-period spend.
- Arithmetic is exact. Overflow throws `ArithmeticException` and mixing
  currencies throws `IllegalArgumentException`; nothing wraps or converts.
- `BigDecimal` is used only at the edges: parsing configuration and rendering.
  `Money.of` accepts only amounts with at most nine decimal places and never
  rounds.
- `TokenPrice` holds a price per million tokens. `costOf` multiplies with
  overflow checks and divides with `Math.ceilDiv`, so every cost **rounds up**
  to the next nano-unit. A reservation therefore never under-reserves, and the
  rounding on a settled cost is at most one nano-unit per request.
- `tokens × price` must fit in a `long`: about 90 million tokens at $100 per
  million tokens in a single request. Beyond that, `costOf` throws rather than
  returning a wrong number.

## Verification

Property tests compare `costOf` with a `BigDecimal` reference rounded up, and
check addition and ordering against `BigInteger` and `BigDecimal`. PIT mutation
testing must kill at least 85% of mutants in core.

## Consequences

- One currency per deployment; conversion is a non-goal.
- Equality and hashing are plain integer comparisons.
