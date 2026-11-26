import { expect, test } from 'vitest';
import { providerRows, statusText } from './health';
import { flow, snapshot } from './snapshots';

const latency = {
  providers: { openai: { p50Millis: 500, p95Millis: 1400 } },
  tenants: {},
  models: {},
};

test('callsAProviderOkWhenItsFailuresStayAtOrBelowFivePercent', () => {
  const live = snapshot({
    flows: [flow({ sent: 95, failed: 2, serverErrors: 2 })],
    latency,
  });
  const [row] = providerRows(live, ['openai']);
  expect(row?.status).toBe('ok');
  expect(row?.calls).toBe(97);
  expect(row?.failedShare).toBeCloseTo(4.12, 1);
});

test('callsAProviderDegradedAboveFivePercentWithAtLeastTwentyCalls', () => {
  const live = snapshot({ flows: [flow({ sent: 94, failed: 2, serverErrors: 8 })], latency });
  const [row] = providerRows(live, ['openai']);
  expect(row?.status).toBe('degraded');
  expect(statusText.degraded).toBe('Degraded');
});

test('doesNotCallAProviderDegradedOnFewerThanTwentyCalls', () => {
  const live = snapshot({ flows: [flow({ sent: 10, failed: 5 })] });
  expect(providerRows(live, [])[0]?.status).toBe('ok');
});

test('callsAProviderWithoutCallsIdleAndKeepsConfiguredOnesInTheList', () => {
  const live = snapshot({ flows: [flow({ sent: 30 })], inFlight: { ollama: 1 } });
  const rows = providerRows(live, ['ollama', 'anthropic', 'openai']);
  expect(rows.map((row) => [row.name, row.status])).toEqual([
    ['openai', 'ok'],
    ['anthropic', 'idle'],
    ['ollama', 'idle'],
  ]);
  expect(rows[2]?.inFlight).toBe(1);
  expect(statusText.idle).toBe('Idle');
  expect(statusText.ok).toBe('OK');
});

test('showsAProviderOnlyTheFlowsKnowAboutAndSumsFallbacks', () => {
  const live = snapshot({
    flows: [
      flow({ provider: 'x', sent: 1, fallbacks: 2 }),
      flow({ provider: 'x', model: 'other', sent: 1, fallbacks: 3 }),
    ],
  });
  const rows = providerRows(live, []);
  expect(rows).toHaveLength(1);
  expect(rows[0]?.fallbacks).toBe(5);
});

test('givesAP95OnlyWithTwentyRespondedCalls', () => {
  const enough = snapshot({ flows: [flow({ sent: 20 })], latency });
  expect(providerRows(enough, [])[0]?.p95Millis).toBe(1400);
  const few = snapshot({ flows: [flow({ sent: 19, failed: 5 })], latency });
  expect(providerRows(few, [])[0]?.p95Millis).toBeNull();
  expect(providerRows(snapshot({ flows: [flow({ sent: 50 })] }), [])[0]?.p95Millis).toBeNull();
});

test('doesNotCountAClientClosureAsAFailureForAProvider', () => {
  const live = snapshot({ flows: [flow({ sent: 30, failed: 10, clientClosed: 10 })] });
  const [row] = providerRows(live, []);
  expect(row?.failedShare).toBe(0);
  expect(row?.status).toBe('ok');
});
