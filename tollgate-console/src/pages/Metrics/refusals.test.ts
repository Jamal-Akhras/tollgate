import { expect, test } from 'vitest';
import { guardRows, reasonLabel, reasons, redacted } from './refusals';
import { flow, snapshot } from './snapshots';

test('labelsTheKnownRefusalCodesAndShowsOthersAsTheyAre', () => {
  expect(reasonLabel('policy_denied')).toBe('Refused by a guard');
  expect(reasonLabel('budget_exceeded')).toBe('Over budget');
  expect(reasonLabel('too_large')).toBe('Too large');
  expect(reasonLabel('invalid_data_class')).toBe('Data class not allowed');
  expect(reasonLabel('redaction_timeout')).toBe('Redaction timed out');
  expect(reasonLabel('something_new')).toBe('something_new');
});

test('sumsRefusalsByCodeAcrossFlowsMostFirst', () => {
  const live = snapshot({
    flows: [
      flow({ refused: { policy_denied: 3, too_large: 1 } }),
      flow({ model: 'b', refused: { policy_denied: 2, budget_exceeded: 7 } }),
    ],
  });
  expect(reasons(live).map((row) => [row.code, row.count])).toEqual([
    ['budget_exceeded', 7],
    ['policy_denied', 5],
    ['too_large', 1],
  ]);
});

test('addsTheRateLimitedCountLastWhenAboveZero', () => {
  const live = snapshot({
    flows: [flow({ refused: { policy_denied: 1 } })],
    limited: { a: 2, b: 3 },
  });
  const rows = reasons(live);
  expect(rows[rows.length - 1]).toEqual({
    code: null,
    label: 'Rate limited, since the start',
    count: 5,
  });
  expect(reasons(snapshot({ limited: { a: 0 } }))).toEqual([]);
});

test('sumsGuardsAcrossTenantsMostRefusalsFirst', () => {
  const live = snapshot({
    guards: [
      { tenant: 'a', guard: 'data-class', allowed: 10, refused: 1, monitored: 0, lastRefused: null },
      { tenant: 'b', guard: 'data-class', allowed: 5, refused: 2, monitored: 1, lastRefused: null },
      { tenant: 'a', guard: 'tool-names', allowed: 9, refused: 0, monitored: 4, lastRefused: null },
      { tenant: 'a', guard: 'request-size', allowed: 9, refused: 8, monitored: 0, lastRefused: null },
    ],
  });
  expect(guardRows(live)).toEqual([
    { guard: 'request-size', allowed: 9, refused: 8, monitored: 0 },
    { guard: 'data-class', allowed: 15, refused: 3, monitored: 1 },
    { guard: 'tool-names', allowed: 9, refused: 0, monitored: 4 },
  ]);
});

test('countsRedactedValuesAcrossFlows', () => {
  expect(redacted(snapshot({ flows: [flow({ redacted: 4 }), flow({ redacted: 3 })] }))).toBe(7);
});
