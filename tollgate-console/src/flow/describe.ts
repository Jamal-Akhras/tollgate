import type {
  BudgetState,
  ConfigView,
  Flow,
  GuardView,
  LiveSnapshot,
  PolicyView,
  TenantView,
} from '../api/types';
import type { PillState } from '../ui/Pill';
import { formatBytes, formatCount } from '../ui/format';
import { moneyText } from '../ui/Money';

export type Check = {
  id: string;
  label: string;
  detail: string;
  state: PillState;
  guard: string | null;
};

export type ProviderNode = {
  name: string;
  region: string | null;
  reachable: boolean;
  fallbackOnly: boolean;
  calls: number;
  spend: string | null;
  inFlight: number;
  caption: string;
};

function plural(n: number, word: string): string {
  return `${n} ${word}${n === 1 ? '' : 's'}`;
}

function sumDecimals(values: string[]): string {
  const scale = Math.max(0, ...values.map((value) => value.split('.')[1]?.length ?? 0));
  const total = values.reduce((sum, value) => {
    const [whole = '0', fraction = ''] = value.split('.');
    return sum + BigInt(whole + fraction.padEnd(scale, '0'));
  }, 0n);
  const digits = total.toString().padStart(scale + 1, '0');
  const whole = digits.slice(0, digits.length - scale);
  const fraction = digits.slice(digits.length - scale).replace(/0+$/, '');
  return fraction ? `${whole}.${fraction}` : whole;
}

function flowsOf(live: LiveSnapshot | null, tenant: string): Flow[] {
  return live ? live.flows.filter((flow) => flow.tenant === tenant) : [];
}

function refusals(flow: Flow): number {
  return Object.values(flow.refused).reduce((sum, count) => sum + count, 0);
}

export function callsOf(flows: Flow[]): number {
  return flows.reduce((sum, flow) => sum + flow.sent + flow.failed + refusals(flow), 0);
}

export function tenantCalls(live: LiveSnapshot | null, tenant: string): number {
  return callsOf(flowsOf(live, tenant));
}

export function tenantCaption(config: ConfigView, tenant: TenantView): string {
  const keys = config.keys.filter((key) => key.tenant === tenant.name).length;
  return `${plural(keys, 'key')}, ${tenant.dataClass ?? 'no data class'}`;
}

function guardDetail(guard: GuardView, tenant: TenantView): string {
  if (guard.mode === 'monitor') {
    return 'monitor';
  }
  if (guard.maxBytes !== null) {
    return formatBytes(guard.maxBytes);
  }
  if (guard.regions !== null) {
    const regions = tenant.dataClass === null ? undefined : guard.regions[tenant.dataClass];
    return `${tenant.dataClass ?? 'no class'} → ${regions && regions.length > 0 ? regions.join(', ') : 'none'}`;
  }
  if (guard.refuse && guard.refuse.length > 0) {
    return `${guard.refuse.length} refused`;
  }
  if (guard.allow && guard.allow.length > 0) {
    return `${guard.allow.length} allowed`;
  }
  return 'any tool';
}

function detectors(map: Record<string, string[]>, dataClass: string | null): number {
  return dataClass === null
    ? 0
    : Object.values(map).filter((classes) => classes.includes(dataClass)).length;
}

function personalData(policy: PolicyView | undefined, tenant: TenantView): string | null {
  if (!policy) {
    return null;
  }
  const redacted = detectors(policy.redact, tenant.dataClass);
  const refused = detectors(policy.refuse, tenant.dataClass);
  const parts = [
    ...(redacted > 0 ? [`${redacted} redacted`] : []),
    ...(refused > 0 ? [`${refused} refused`] : []),
  ];
  return parts.length > 0 ? parts.join(', ') : null;
}

function limitDetail(tenant: TenantView): string | null {
  const { requestsPerMinute, tokensPerMinute, concurrent } = tenant.limits;
  if (requestsPerMinute !== null) {
    return `${formatCount(requestsPerMinute)}/min`;
  }
  if (tokensPerMinute !== null) {
    return `${formatCount(tokensPerMinute)} tokens/min`;
  }
  return concurrent !== null ? `${concurrent} at once` : null;
}

function budgetDetail(tenant: TenantView, budgets: BudgetState[]): string | null {
  if (tenant.budget === null) {
    return null;
  }
  const { limit, currency, period } = tenant.budget;
  const state = budgets.find((budget) => budget.tenant === tenant.name);
  return state
    ? `${moneyText({ amount: state.spent, currency })} / ${moneyText({ amount: limit, currency })}`
    : `${moneyText({ amount: limit, currency })} ${period}`;
}

export function checksFor(
  config: ConfigView,
  tenantName: string,
  budgets: BudgetState[],
  live: LiveSnapshot | null,
): Check[] {
  const tenant = config.tenants.find((item) => item.name === tenantName);
  if (!tenant) {
    return [];
  }
  const policy = config.policies.find((item) => item.name === tenant.policy);
  const checks: Check[] = [
    {
      id: 'request-features',
      label: 'Request review',
      detail: 'built in',
      state: 'ok',
      guard: 'request-features',
    },
  ];
  (policy?.guards ?? []).forEach((guard, index) => {
    checks.push({
      id: `guard-${index}`,
      label: guard.type,
      detail: guardDetail(guard, tenant),
      state: guard.mode === 'monitor' ? 'monitor' : 'ok',
      guard: guard.type,
    });
  });
  const personal = personalData(policy, tenant);
  if (personal !== null) {
    checks.push({
      id: 'personal-data',
      label: 'Personal data',
      detail: personal,
      state: 'ok',
      guard: 'personal-data',
    });
  }
  const budget = budgetDetail(tenant, budgets);
  if (budget !== null) {
    checks.push({ id: 'budget', label: 'Budget', detail: budget, state: 'ok', guard: null });
  }
  const limits = limitDetail(tenant);
  if (limits !== null) {
    checks.push({
      id: 'rate-limits',
      label: 'Rate limits',
      detail: limits,
      state: 'ok',
      guard: null,
    });
  }
  const off = config.audit.mode === 'off';
  checks.push({
    id: 'audit',
    label: 'Signed audit trail',
    detail: off ? 'off' : live?.lastSeal ? `batch ${live.lastSeal.batch}` : config.audit.mode,
    state: off ? 'off' : 'ok',
    guard: null,
  });
  return checks;
}

function reachedProviders(config: ConfigView, tenant: TenantView): Set<string> {
  const seen = new Set<string>();
  const queue = [...tenant.models];
  while (queue.length > 0) {
    const name = queue.shift();
    const model = config.models.find((item) => item.name === name);
    if (model && !seen.has(model.name)) {
      seen.add(model.name);
      queue.push(...model.fallback);
    }
  }
  return new Set(
    [...seen].flatMap((name) => config.models.find((item) => item.name === name)?.provider ?? []),
  );
}

function reachable(
  policy: PolicyView | undefined,
  dataClass: string | null,
  region: string | null,
): boolean {
  const guard = policy?.guards.find((item) => item.type === 'data-class' && item.mode === 'enforce');
  if (!guard) {
    return true;
  }
  const allowed = dataClass === null ? undefined : guard.regions?.[dataClass];
  return region !== null && (allowed?.includes(region) ?? false);
}

export function providersFor(
  config: ConfigView,
  tenantName: string,
  live: LiveSnapshot | null,
): ProviderNode[] {
  const tenant = config.tenants.find((item) => item.name === tenantName);
  if (!tenant) {
    return [];
  }
  const policy = config.policies.find((item) => item.name === tenant.policy);
  const direct = new Set(
    tenant.models.flatMap((name) => config.models.find((item) => item.name === name)?.provider ?? []),
  );
  const reached = reachedProviders(config, tenant);
  const flows = flowsOf(live, tenantName);
  return config.providers
    .filter((provider) => reached.has(provider.name))
    .map((provider) => {
      const own = flows.filter((flow) => flow.provider === provider.name);
      const calls = own.reduce((sum, flow) => sum + flow.sent + flow.failed, 0);
      const currencies = [...new Set(own.flatMap((flow) => Object.keys(flow.cost)))];
      const amounts = currencies.map((currency) => ({
        currency,
        amount: sumDecimals(own.flatMap((flow) => flow.cost[currency] ?? [])),
      }));
      const paid = amounts.filter((money) => Number(money.amount) !== 0);
      const spend =
        calls === 0
          ? null
          : paid.length === 0
            ? 'free'
            : paid.map((money) => moneyText(money)).join(' + ');
      const isReachable = reachable(policy, tenant.dataClass, provider.region);
      const fallbackOnly = !direct.has(provider.name);
      const inFlight = live?.inFlight[provider.name] ?? 0;
      const parts = [
        provider.region ?? 'no region',
        ...(isReachable
          ? [plural(calls, 'call'), ...(spend ? [spend] : [])]
          : [tenant.dataClass ? `not for ${tenant.dataClass}` : 'needs a data class']),
        ...(fallbackOnly ? ['fallback'] : []),
        ...(inFlight > 0 ? [`${inFlight} in flight`] : []),
      ];
      return {
        name: provider.name,
        region: provider.region,
        reachable: isReachable,
        fallbackOnly,
        calls,
        spend,
        inFlight,
        caption: parts.join(' · '),
      };
    });
}
