import type { Totals } from '../../api/types';
import { moneyText } from '../../ui/Money';

export const enoughCalls = 20;

export type Change = { text: string; up: boolean };

type Failures = Pick<Totals, 'failed' | 'clientClosed' | 'serverErrors'>;
type Counts = Failures & Pick<Totals, 'sent' | 'refused'>;

export function totalCalls(counts: Pick<Totals, 'sent' | 'refused' | 'failed'>): number {
  return counts.sent + counts.refused + counts.failed;
}

export function failedOf(counts: Failures): number {
  return counts.failed - counts.clientClosed + counts.serverErrors;
}

export function share(part: number, whole: number): number | null {
  return whole > 0 ? (part / whole) * 100 : null;
}

export function failedShare(counts: Counts): number | null {
  return share(failedOf(counts), totalCalls(counts));
}

export function refusedShare(counts: Counts): number | null {
  return share(counts.refused, totalCalls(counts));
}

export function percentText(value: number | null): string {
  if (value === null) {
    return '–';
  }
  const rounded = value < 10 ? Math.round(value * 10) / 10 : Math.round(value);
  return `${rounded}%`;
}

export function perMinute(count: number, seconds: number): number | null {
  return seconds > 0 ? (count * 60) / seconds : null;
}

export function changeText(
  current: number | null,
  previous: number | null,
  since: string,
): Change | null {
  if (current === null || previous === null) {
    return null;
  }
  if (previous === 0) {
    return current === 0
      ? { text: `no change vs ${since}`, up: false }
      : { text: `up from none in ${since}`, up: true };
  }
  const percent = Math.round(((current - previous) / previous) * 100);
  if (percent === 0) {
    return { text: `no change vs ${since}`, up: false };
  }
  return { text: `${percent > 0 ? '+' : ''}${percent}% vs ${since}`, up: percent > 0 };
}

export function directionText(current: number, previous: number, since: string): Change {
  if (current === previous) {
    return { text: `no change vs ${since}`, up: false };
  }
  return { text: `${current > previous ? 'higher' : 'lower'} vs ${since}`, up: current > previous };
}

export function pointsText(
  current: number | null,
  previous: number | null,
  since: string,
): Change | null {
  if (current === null || previous === null) {
    return null;
  }
  const points = Math.round((current - previous) * 10) / 10;
  if (points === 0) {
    return { text: `no change vs ${since}`, up: false };
  }
  return { text: `${points > 0 ? '+' : ''}${points.toFixed(1)} pt vs ${since}`, up: points > 0 };
}

export function amountText(amount: number, currency: string): string {
  return moneyText({ amount: String(Number(amount.toFixed(6))), currency, short: true });
}

export function withCurrencies(
  cost: Record<string, string>,
  known: string[],
): Record<string, string> {
  return { ...Object.fromEntries(known.map((currency) => [currency, '0'])), ...cost };
}

export function spendText(cost: Record<string, string>): string {
  const parts = Object.entries(cost)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([currency, amount]) => moneyText({ amount, currency, short: true }));
  return parts.length === 0 ? '–' : parts.join(' + ');
}

export function perHour(cost: Record<string, string>, seconds: number): Record<string, number> {
  if (seconds <= 0) {
    return {};
  }
  return Object.fromEntries(
    Object.entries(cost).map(([currency, amount]) => [currency, (Number(amount) * 3600) / seconds]),
  );
}

export function paceText(cost: Record<string, string>, seconds: number): string {
  if (seconds <= 0) {
    return '–';
  }
  const rates = Object.entries(perHour(cost, seconds))
    .filter(([, rate]) => rate > 0)
    .sort(([a], [b]) => a.localeCompare(b));
  if (rates.length === 0) {
    return 'No spend in this window';
  }
  const text = rates.map(([currency, rate]) => amountText(rate, currency)).join(' + ');
  return `${text} an hour at this pace`;
}

export function spendChange(current: Totals, previous: Totals, since: string): Change | null {
  const currencies = new Set([...Object.keys(current.cost), ...Object.keys(previous.cost)]);
  const [only] = [...currencies];
  if (currencies.size !== 1 || only === undefined) {
    return null;
  }
  return changeText(
    perMinute(Number(current.cost[only] ?? 0), current.seconds),
    perMinute(Number(previous.cost[only] ?? 0), previous.seconds),
    since,
  );
}

export function plural(count: number, word: string): string {
  return `${count} ${word}${count === 1 ? '' : 's'}`;
}
