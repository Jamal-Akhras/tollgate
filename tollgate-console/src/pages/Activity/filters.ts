import type { CallView } from '../../api/types';

export type ResultKind = 'sent' | 'refused' | 'failed' | 'dry run' | 'waiting';

export const resultKinds: ResultKind[] = ['sent', 'refused', 'failed', 'dry run', 'waiting'];

export type Filters = { tenant: string; result: string; guard: string };

export function resultKind(call: CallView): ResultKind {
  if (!call.allowed) {
    return 'refused';
  }
  const outcome = call.outcome;
  if (!outcome) {
    return 'waiting';
  }
  if (outcome.result === 'responded') {
    return 'sent';
  }
  if (outcome.result === 'dry_run') {
    return 'dry run';
  }
  return 'failed';
}

export function endpointName(endpoint: string): string {
  if (endpoint === 'openai') {
    return 'OpenAI';
  }
  if (endpoint === 'anthropic') {
    return 'Anthropic';
  }
  return endpoint;
}

export function wouldRefuse(call: CallView, guard: string): boolean {
  return call.steps.some((step) => step.guard === guard && !step.allowed);
}

export function filterCalls(calls: CallView[], filters: Filters): CallView[] {
  return calls.filter(
    (call) =>
      (filters.tenant === '' || call.tenant === filters.tenant) &&
      (filters.result === '' || resultKind(call) === filters.result) &&
      (filters.guard === '' || wouldRefuse(call, filters.guard)),
  );
}

export function tenantsIn(calls: CallView[]): string[] {
  return [...new Set(calls.map((call) => call.tenant))].sort();
}

export function guardsIn(calls: CallView[]): string[] {
  return [...new Set(calls.flatMap((call) => call.steps.map((step) => step.guard)))].sort();
}

export function readFilters(query: Record<string, string>): Filters {
  return { tenant: query.tenant ?? '', result: query.result ?? '', guard: query.guard ?? '' };
}

export function filterQuery(filters: Filters): Record<string, string> {
  return Object.fromEntries(Object.entries(filters).filter(([, value]) => value !== ''));
}
