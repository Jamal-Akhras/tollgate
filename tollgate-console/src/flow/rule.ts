import type { BudgetState, ConfigView, GuardView, LiveSnapshot, TenantView } from '../api/types';
import { formatBytes, formatCount } from '../ui/format';
import { moneyText } from '../ui/Money';
import { tenantCalls, type Check } from './describe';

export type Fact = { label: string; value: string };

export type GuardTotals = { allowed: number; refused: number; monitored: number };

export type Rule = {
  title: string;
  what: string;
  calls: number;
  counts: GuardTotals | null;
  perCheck: boolean;
  facts: Fact[];
  mode: 'enforce' | 'monitor' | 'built-in';
  guard: { policy: string; index: number; type: string } | null;
  budget: { state: BudgetState | null; tenant: TenantView } | null;
};

function list(items: string[] | null | undefined, none: string): string {
  return items && items.length > 0 ? items.join(', ') : none;
}

function refusedFor(live: LiveSnapshot | null, tenant: string, code: string): number {
  return (live?.flows ?? [])
    .filter((flow) => flow.tenant === tenant)
    .reduce((sum, flow) => sum + (flow.refused[code] ?? 0), 0);
}

function refusedFact(live: LiveSnapshot, tenant: string, code: string): Fact {
  return { label: `Refused, ${code}`, value: String(refusedFor(live, tenant, code)) };
}

function guardFacts(guard: GuardView, tenant: TenantView): { what: string; facts: Fact[] } {
  if (guard.type === 'request-size') {
    return {
      what: 'Refuses a request that is larger than the limit.',
      facts: [
        {
          label: 'Largest request',
          value:
            guard.maxBytes === null
              ? 'no limit'
              : `${formatBytes(guard.maxBytes)} (${formatCount(guard.maxBytes)} bytes)`,
        },
      ],
    };
  }
  if (guard.type === 'data-class') {
    const regions = Object.entries(guard.regions ?? {});
    return {
      what: "Sends a request only to providers in a region its tenant's data class may reach.",
      facts: [
        { label: 'Data class of this tenant', value: tenant.dataClass ?? 'none' },
        ...regions.map(([dataClass, allowed]) => ({
          label: `Regions for ${dataClass}`,
          value: list(allowed, 'none'),
        })),
      ],
    };
  }
  if (guard.type === 'tool-names') {
    return {
      what: 'Checks the names of the tools a request carries against two lists.',
      facts: [
        { label: 'Allowed tools', value: list(guard.allow, 'any') },
        { label: 'Refused tools', value: list(guard.refuse, 'none') },
      ],
    };
  }
  return { what: `Runs the ${guard.type} guard on every request.`, facts: [] };
}

function detectorsFor(map: Record<string, string[]>, dataClass: string | null): string[] {
  return Object.entries(map)
    .filter(([, classes]) => dataClass !== null && classes.includes(dataClass))
    .map(([detector]) => detector);
}

export function describeRule(
  config: ConfigView,
  tenantName: string,
  check: Check,
  live: LiveSnapshot | null,
  budgets: BudgetState[],
): Rule | null {
  const tenant = config.tenants.find((item) => item.name === tenantName);
  const policy = config.policies.find((item) => item.name === tenant?.policy);
  if (!tenant || !policy) {
    return null;
  }
  const own = live?.guards.find(
    (item) => item.tenant === tenantName && item.guard === check.guard,
  );
  const counts: GuardTotals | null =
    live && check.guard !== null
      ? {
          allowed: own?.allowed ?? 0,
          refused: own?.refused ?? 0,
          monitored: own?.monitored ?? 0,
        }
      : null;
  const calls = counts
    ? counts.allowed + counts.refused + counts.monitored
    : tenantCalls(live, tenantName);
  const rule: Rule = {
    title: check.label,
    what: '',
    calls,
    counts,
    perCheck: check.guard !== null,
    facts: [],
    mode: 'built-in',
    guard: null,
    budget: null,
  };

  const index = check.id.startsWith('guard-') ? Number(check.id.slice('guard-'.length)) : -1;
  const guard = policy.guards[index];
  if (guard) {
    const detail = guardFacts(guard, tenant);
    return {
      ...rule,
      what: detail.what,
      facts: detail.facts,
      mode: guard.mode === 'monitor' ? 'monitor' : 'enforce',
      guard: { policy: policy.name, index, type: guard.type },
    };
  }
  if (check.id === 'request-features') {
    return {
      ...rule,
      what: 'Looks at what a request asks for, such as tools, before the other checks run.',
      facts: Object.entries(policy.features).map(([feature, names]) => ({
        label: `Features: ${feature}`,
        value: list(names, 'none'),
      })),
    };
  }
  if (check.id === 'personal-data') {
    const redacted = detectorsFor(policy.redact, tenant.dataClass);
    const refused = detectorsFor(policy.refuse, tenant.dataClass);
    return {
      ...rule,
      what:
        "Looks for personal data in what is sent, and redacts it or refuses the call, by this tenant's data class.",
      facts: [
        { label: 'Redacted', value: list(redacted, 'nothing') },
        { label: 'Refused', value: list(refused, 'nothing') },
      ],
    };
  }
  if (check.id === 'budget' && tenant.budget) {
    const { limit, currency, period } = tenant.budget;
    const state = budgets.find((item) => item.tenant === tenantName) ?? null;
    const money = (amount: string) => moneyText({ amount, currency });
    return {
      ...rule,
      what:
        'Refuses calls once the tenant has spent its limit. Money held for calls in flight counts as spent.',
      facts: [
        { label: 'Limit', value: `${money(limit)} ${period}` },
        ...(state
          ? [
              { label: 'Spent', value: money(state.spent) },
              { label: 'Held for calls in flight', value: money(state.reserved) },
              { label: 'Available', value: money(state.available) },
            ]
          : []),
        ...(live
          ? [refusedFact(live, tenantName, 'budget_exceeded')]
          : []),
      ],
      budget: { state, tenant },
    };
  }
  if (check.id === 'rate-limits') {
    const { requestsPerMinute, tokensPerMinute, concurrent } = tenant.limits;
    const limits: Fact[] = [
      ...(requestsPerMinute === null
        ? []
        : [{ label: 'Requests per minute', value: formatCount(requestsPerMinute) }]),
      ...(tokensPerMinute === null
        ? []
        : [{ label: 'Tokens per minute', value: formatCount(tokensPerMinute) }]),
      ...(concurrent === null ? [] : [{ label: 'Calls at once', value: String(concurrent) }]),
    ];
    return {
      ...rule,
      what: "Refuses calls that go over the tenant's limits.",
      facts: [
        ...limits,
        ...(live
          ? [refusedFact(live, tenantName, 'rate_limited')]
          : []),
      ],
    };
  }
  if (check.id === 'audit') {
    const seal = live?.lastSeal ?? null;
    return {
      ...rule,
      what: 'Writes every decision to the signed audit trail, sealed in batches.',
      facts: [
        { label: 'Mode', value: config.audit.mode },
        ...(seal
          ? [{ label: 'Last sealed batch', value: `${seal.batch}, ${seal.records} records` }]
          : []),
      ],
    };
  }
  return { ...rule, what: `Runs the ${check.label} check.` };
}
