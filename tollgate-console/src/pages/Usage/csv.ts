import type { Row, UsageReport } from '../../api/types';

const header = [
  'key',
  'calls',
  'refused',
  'refused_codes',
  'unfinished',
  'input_tokens',
  'output_tokens',
  'cache_read_tokens',
  'cache_write_tokens',
  'cost',
];

export function refusedTotal(row: Row): number {
  return Object.values(row.refused).reduce((sum, count) => sum + count, 0);
}

function field(value: string | number): string {
  const text = String(value);
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

function line(row: Row): string {
  const codes = Object.entries(row.refused).map(([code, count]) => `${code}:${count}`);
  const cost = Object.entries(row.cost).map(([currency, amount]) => `${amount} ${currency}`);
  return [
    row.key,
    row.calls,
    refusedTotal(row),
    codes.join(' '),
    row.unfinished,
    row.tokens.input,
    row.tokens.output,
    row.tokens.cacheRead,
    row.tokens.cacheWrite,
    cost.join(' '),
  ]
    .map(field)
    .join(',');
}

export function toCsv(report: UsageReport): string {
  return [header.join(','), ...report.rows.map(line)].join('\r\n');
}
