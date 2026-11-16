import { expect, test } from 'vitest';
import { usageBusyFixture } from '../../api/fixtures';
import type { UsageReport } from '../../api/types';
import { refusedTotal, toCsv } from './csv';

test('writesAHeaderAndARowForEachKey', () => {
  const lines = toCsv(usageBusyFixture).split('\r\n');
  expect(lines).toHaveLength(4);
  expect(lines[0]).toBe(
    'key,calls,refused,refused_codes,unfinished,input_tokens,output_tokens,' +
      'cache_read_tokens,cache_write_tokens,cost',
  );
  expect(lines[3]).toBe(
    'support-bot,9120,134,data_class:120 tool_not_allowed:14,3,5120000,2310000,410000,90000,41.18 USD',
  );
});

test('quotesAKeyWithACommaAndAQuote', () => {
  const report: UsageReport = {
    by: 'key',
    from: '2026-11-01T00:00:00Z',
    to: '2026-11-17T00:00:00Z',
    rows: [
      {
        key: 'say "hi", bob',
        calls: 1,
        unfinished: 0,
        tokens: { input: 1, output: 2, cacheRead: 0, cacheWrite: 0 },
        cost: {},
        refused: {},
      },
    ],
  };
  expect(toCsv(report).split('\r\n')[1]).toBe('"say ""hi"", bob",1,0,,0,1,2,0,0,');
});

test('addsUpTheRefusalCodes', () => {
  const row = usageBusyFixture.rows[2];
  expect(row && refusedTotal(row)).toBe(134);
});
