import type { Flow, GuardCount } from '../api/types';
import type { Check } from './describe';
import type { Layout, Rect } from './layout';

export type Point = { x: number; y: number };

export type LineKind = 'sent' | 'fallback' | 'refused' | 'idle';

export type Line = {
  id: string;
  kind: LineKind;
  moving: boolean;
  fast: boolean;
  faint: boolean;
  d: string;
  from: Point;
  pill: number | null;
};

export type Origin = { name: string; rect: Rect; selected: boolean };

export type LineInput = {
  tenants: Origin[];
  selected: string;
  flows: Flow[];
  providers: string[];
  checks: Check[];
  guards: GuardCount[];
  preview?: boolean;
};

const recentMillis = 10_000;
const stub = 8;

export function curve(from: Point, to: Point): string {
  const mx = (from.x + to.x) / 2;
  return `M ${from.x} ${from.y} C ${mx} ${from.y}, ${mx} ${to.y}, ${to.x} ${to.y}`;
}

function recent(instant: string | null, now: number): boolean {
  return instant !== null && now - Date.parse(instant) <= recentMillis;
}

function latest(instants: (string | null)[]): string | null {
  return instants.reduce<string | null>(
    (best, instant) => (instant !== null && (best === null || instant > best) ? instant : best),
    null,
  );
}

function lastSeen(flows: Flow[]): string | null {
  return latest(flows.flatMap((flow) => [flow.lastSent, flow.lastFallback, flow.lastRefused]));
}

function calls(flow: Flow): number {
  return flow.sent + flow.failed + Object.values(flow.refused).reduce((sum, n) => sum + n, 0);
}

function right(rect: Rect, gap = 0): Point {
  return { x: rect.x + rect.w + gap, y: rect.y + rect.h / 2 };
}

function left(rect: Rect, gap = 0): Point {
  return { x: rect.x - gap, y: rect.y + rect.h / 2 };
}

function refusingPill(checks: Check[], guards: GuardCount[]): number {
  const worst = guards.reduce<GuardCount | null>(
    (best, guard) =>
      guard.lastRefused !== null && (best === null || guard.lastRefused > (best.lastRefused ?? ''))
        ? guard
        : best,
    null,
  );
  const named = worst ? checks.findIndex((check) => check.guard === worst.guard) : -1;
  if (named >= 0) {
    return named;
  }
  return Math.max(
    0,
    checks.findIndex((check) => check.id === 'budget'),
  );
}

function line(id: string, kind: LineKind, from: Point, to: Point, fast = false): Line {
  return {
    id,
    kind,
    moving: kind !== 'idle',
    fast,
    faint: false,
    d: curve(from, to),
    from,
    pill: null,
  };
}

export function linesFor(layout: Layout, input: LineInput, now: number): Line[] {
  const chosen = input.tenants.find((tenant) => tenant.selected);
  const { providers, checks } = input;
  const flows = input.flows.filter((flow) => flow.tenant === input.selected);
  const guards = input.guards.filter((guard) => guard.tenant === input.selected);
  const lines: Line[] = [];

  if (input.preview) {
    if (!chosen) {
      return lines;
    }
    const origin = right(chosen.rect, stub);
    providers.forEach((name, index) => {
      const card = layout.providers[index];
      if (card) {
        lines.push(line(`direct-${name}`, 'idle', origin, left(card, stub)));
      }
    });
    return lines;
  }

  const intake = left(layout.policy);
  const others = input.tenants.filter((tenant) => !tenant.selected);
  [...others, ...(chosen ? [chosen] : [])].forEach((tenant) => {
    const own = input.flows.filter((flow) => flow.tenant === tenant.name);
    const kind = own.some((flow) => calls(flow) > 0) ? 'sent' : 'idle';
    const drawn = line(
      `tenant-${tenant.name}`,
      kind,
      right(tenant.rect, stub),
      intake,
      recent(lastSeen(own), now),
    );
    lines.push({ ...drawn, faint: !tenant.selected });
  });

  const exit = right(layout.policy);
  providers.forEach((name, index) => {
    const card = layout.providers[index];
    if (!card) {
      return;
    }
    const own = flows.filter((flow) => flow.provider === name);
    const sentAt = latest(own.map((flow) => flow.lastSent));
    const fallbackAt = latest(own.map((flow) => flow.lastFallback));
    const fallbacks = own.reduce((sum, flow) => sum + flow.fallbacks, 0);
    const answered = own.reduce((sum, flow) => sum + flow.sent, 0);
    const fallback = fallbackAt !== null && (recent(fallbackAt, now) || fallbacks >= answered);
    const sent = sentAt !== null || answered > 0;
    const kind = fallback ? 'fallback' : sent ? 'sent' : 'idle';
    lines.push(
      line(`provider-${name}`, kind, exit, left(card, stub), recent(sentAt, now) || recent(fallbackAt, now)),
    );
  });

  const refusedAt = latest([
    ...flows.map((flow) => flow.lastRefused),
    ...guards.map((guard) => guard.lastRefused),
  ]);
  const refused = refusedAt !== null || flows.some((flow) => Object.keys(flow.refused).length > 0);
  if (!refused) {
    return lines;
  }
  const sink = left(layout.refused, stub);
  const refusing = checks
    .map((check, index) => ({
      index,
      count: guards.find((guard) => guard.guard === check.guard && guard.refused > 0),
    }))
    .filter((item) => item.count !== undefined);
  const overBudget = flows.some((flow) => (flow.refused.budget_exceeded ?? 0) > 0);
  const budget = checks.findIndex((check) => check.id === 'budget');
  const pills = new Map<number, string | null>(
    refusing.map((item) => [item.index, item.count?.lastRefused ?? null]),
  );
  if (overBudget && budget >= 0) {
    pills.set(budget, latest(flows.map((flow) => flow.lastRefused)));
  }
  if (pills.size === 0) {
    pills.set(refusingPill(checks, guards), refusedAt);
  }
  [...pills].forEach(([index, at]) => {
    const pill = layout.pills[index];
    if (pill) {
      lines.push({
        ...line(`refused-${checks[index]?.id}`, 'refused', right(pill), sink, recent(at, now)),
        pill: index,
      });
    }
  });
  return lines;
}
