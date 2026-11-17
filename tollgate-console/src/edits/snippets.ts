import type { BudgetView, ConfigView } from '../api/types';

export type Snippet = { note: string; code: string | null };

export function guardMode(
  policy: string,
  index: number,
  type: string,
  mode: 'enforce' | 'monitor',
): Snippet {
  const where = `In policies.${policy}.guards[${index}] (${type})`;
  const note =
    mode === 'monitor'
      ? `${where}, add this line. The guard still runs and records what it would have refused, but refuses nothing.`
      : `${where}, delete the mode: monitor line or set it as below. Calls it would refuse are refused again.`;
  const code = [
    'policies:',
    `  ${policy}:`,
    '    guards:',
    `      - type: ${type}`,
    `        mode: ${mode}`,
  ].join('\n');
  return { note, code };
}

export function addKey(tenant: string): Snippet {
  return {
    note: `Run this where tollgate.yaml is. It prints the key once and the line to add under keys: for ${tenant}. Tollgate keeps only the key's hash.`,
    code: [
      `tollgate keys create --tenant ${tenant}`,
      '',
      'keys:',
      `  - { id: <printed id>, tenant: ${tenant}, sha256: <printed hash> }`,
    ].join('\n'),
  };
}

export function setBudget(tenant: string, budget: BudgetView | null): Snippet {
  const limit = budget?.limit ?? '50.00';
  const period = budget?.period ?? 'monthly';
  const note = budget
    ? `In tenants.${tenant}, change the budget line. The period is daily, monthly or total.`
    : `In tenants.${tenant}, add a budget line. The period is daily, monthly or total. The count starts at zero when the config is reloaded.`;
  const code = ['tenants:', `  ${tenant}:`, `    budget: { limit: ${limit}, period: ${period} }`];
  return { note, code: code.join('\n') };
}

export function removeKey(id: string, tenant: string): Snippet {
  return {
    note: "Delete this line under keys:. Applications using the key get 401 once the config is reloaded. It can't be undone: the key itself was never kept.",
    code: ['keys:', `  - { id: ${id}, tenant: ${tenant}, sha256: … }`].join('\n'),
  };
}

export function editSection(section: string): Snippet {
  return { note: `Edit the ${section}: section of tollgate.yaml.`, code: null };
}

export type SectionName = 'tenants' | 'keys' | 'policies' | 'providers' | 'models' | 'dataClasses';

export function forSection(section: SectionName, config: ConfigView): Snippet {
  const tenant = config.tenants.find((item) => item.budget === null) ?? config.tenants[0];
  if (section === 'tenants' && tenant) {
    return setBudget(tenant.name, tenant.budget);
  }
  if (section === 'keys' && tenant) {
    return addKey(tenant.name);
  }
  if (section === 'policies') {
    for (const policy of config.policies) {
      const guard = policy.guards[0];
      if (guard) {
        const mode = guard.mode === 'monitor' ? 'enforce' : 'monitor';
        return guardMode(policy.name, 0, guard.type, mode);
      }
    }
  }
  return editSection(section);
}
