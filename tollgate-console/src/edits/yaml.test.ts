import { expect, test } from 'vitest';
import { budgetLines, guardModeLines, newKeyLines, revokedKeyLines } from './yaml';

test('monitoringAGuardAddsTheModeLine', () => {
  expect(guardModeLines('default', 1, 'data-class', 'monitor')).toEqual({
    path: 'policies.default.guards[1] (data-class)',
    removed: [],
    added: ['mode: "monitor"'],
  });
});

test('enforcingAGuardRemovesTheModeLine', () => {
  expect(guardModeLines('default', 0, 'tool-names', 'enforce')).toEqual({
    path: 'policies.default.guards[0] (tool-names)',
    removed: ['mode: "monitor"'],
    added: [],
  });
});

test('aBudgetReplacesItsTwoLines', () => {
  const current = { limit: '50', currency: 'USD', period: 'monthly' };
  expect(budgetLines('support-bot', current, { limit: '30', period: 'daily' })).toEqual({
    path: 'tenants.support-bot.budget',
    removed: ['limit: 50', 'period: "monthly"'],
    added: ['limit: 30', 'period: "daily"'],
  });
});

test('aFirstBudgetOnlyAddsLines', () => {
  expect(budgetLines('my-app', null, { limit: '10', period: 'total' })).toEqual({
    path: 'tenants.my-app.budget',
    removed: [],
    added: ['limit: 10', 'period: "total"'],
  });
});

test('aNewKeyAddsItsEntry', () => {
  expect(newKeyLines('my-app')).toEqual({
    path: 'keys',
    removed: [],
    added: ['- id: (made by Tollgate)', 'tenant: "my-app"', "sha256: (the new key's hash)"],
  });
});

test('aRevokedKeyRemovesItsEntry', () => {
  expect(revokedKeyLines({ id: '3f9a1c2eK7mQ', tenant: 'my-app' })).toEqual({
    path: 'keys',
    removed: ['- id: "3f9a1c2eK7mQ"', 'tenant: "my-app"', 'sha256: (its hash)'],
    added: [],
  });
});
