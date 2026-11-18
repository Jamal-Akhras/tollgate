import type { Flow, GuardCount } from '../api/types';
import type { Check } from './describe';
import type { Layout, Rect } from './layout';

export type Point = { x: number; y: number };

export type LineKind = 'sent' | 'fallback' | 'refused' | 'idle';

export type Line = {
  id: string;
  kind: LineKind;
  moving: boolean;
  d: string;
  from: Point;
  end: Point | null;
  pill: number | null;
};

export type LineInput = {
  flows: Flow[];
  providers: string[];
  checks: Check[];
  guards: GuardCount[];
  preview?: boolean;
};

const recentMillis = 10_000;
const stub = 8;
const crossReach = 40;

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

function line(id: string, kind: LineKind, from: Point, to: Point): Line {
  return { id, kind, moving: kind !== 'idle', d: curve(from, to), from, end: null, pill: null };
}

export function linesFor(layout: Layout, input: LineInput, now: number): Line[] {
  const tenant = layout.tenants[0];
  if (!tenant) {
    return [];
  }
  const { flows, providers, checks, guards } = input;
  const lines: Line[] = [];

  if (input.preview) {
    const origin = right(tenant, stub);
    providers.forEach((name, index) => {
      const card = layout.providers[index];
      if (card) {
        const to = left(card, stub);
        lines.push(line(`direct-${name}`, 'idle', origin, to));
      }
    });
    return lines;
  }

  const active = flows.some(
    (flow) =>
      recent(flow.lastSent, now) || recent(flow.lastFallback, now) || recent(flow.lastRefused, now),
  );
  const intake = { from: right(tenant, stub), to: left(layout.policy) };
  lines.push(line('tenant', active ? 'sent' : 'idle', intake.from, intake.to));

  const exit = right(layout.policy);
  providers.forEach((name, index) => {
    const card = layout.providers[index];
    if (!card) {
      return;
    }
    const own = flows.filter((flow) => flow.provider === name);
    const fallback = own.some((flow) => recent(flow.lastFallback, now));
    const sent = own.some((flow) => recent(flow.lastSent, now));
    const to = left(card, stub);
    lines.push(line(`provider-${name}`, fallback ? 'fallback' : sent ? 'sent' : 'idle', exit, to));
  });

  const refusedAt = latest([
    ...flows.map((flow) => flow.lastRefused),
    ...guards.map((guard) => guard.lastRefused),
  ]);
  const refused = refusedAt !== null || flows.some((flow) => Object.keys(flow.refused).length > 0);
  const pillIndex = refusingPill(checks, guards);
  const pill = layout.pills[pillIndex];
  if (refused && pill) {
    const from = right(pill);
    const end = { x: layout.policy.x + layout.policy.w + crossReach, y: from.y };
    lines.push({
      id: 'refused',
      kind: 'refused',
      moving: recent(refusedAt, now),
      d: curve(from, end),
      from,
      end,
      pill: pillIndex,
    });
  }
  return lines;
}
