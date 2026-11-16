import { expect, test } from 'vitest';
import { configBusyFixture, configFixture } from '../../api/fixtures';
import { addKey, editSection, forSection, guardMode, removeKey, setBudget } from './snippets';

test('guardModeToMonitorShowsTheLineToAdd', () => {
  const snippet = guardMode('default', 1, 'data-class', 'monitor');
  expect(snippet.note).toBe(
    'In policies.default.guards[1] (data-class), add this line. The guard still runs and records what it would have refused, but refuses nothing.',
  );
  expect(snippet.code).toBe(
    'policies:\n  default:\n    guards:\n      - type: data-class\n        mode: monitor',
  );
});

test('guardModeToEnforceShowsTheLineToSet', () => {
  const snippet = guardMode('default', 0, 'tool-names', 'enforce');
  expect(snippet.note).toContain('delete the mode: monitor line');
  expect(snippet.code).toContain('        mode: enforce');
});

test('addKeyGivesTheCommandAndWhereItsLineGoes', () => {
  expect(addKey('support-bot').code).toBe(
    'tollgate keys create --tenant support-bot\n\nkeys:\n  - { id: <printed id>, tenant: support-bot, sha256: <printed hash> }',
  );
});

test('setBudgetSuggestsALimitWhereThereIsNone', () => {
  const snippet = setBudget('my-app', null);
  expect(snippet.code).toBe(
    'tenants:\n  my-app:\n    budget: { limit: 50.00, period: monthly }',
  );
  expect(snippet.note).toContain('add a budget line');
});

test('setBudgetKeepsTheCurrentValues', () => {
  const snippet = setBudget('support-bot', { limit: '150', currency: 'USD', period: 'daily' });
  expect(snippet.code).toBe(
    'tenants:\n  support-bot:\n    budget: { limit: 150, period: daily }',
  );
  expect(snippet.note).toContain('change the budget line');
});

test('removeKeyShowsTheLineToDelete', () => {
  expect(removeKey('a81d07b4', 'support-bot').code).toBe(
    'keys:\n  - { id: a81d07b4, tenant: support-bot, sha256: … }',
  );
});

test('sectionsWithoutAYamlExampleOnlyNameTheSection', () => {
  expect(editSection('models')).toEqual({
    note: 'Edit the models: section of tollgate.yaml.',
    code: null,
  });
  expect(forSection('providers', configFixture).code).toBeNull();
});

test('forSectionPicksSomethingToPointAt', () => {
  expect(forSection('tenants', configBusyFixture).code).toContain('  my-app:');
  expect(forSection('keys', configBusyFixture).code).toContain('--tenant my-app');
  expect(forSection('policies', configBusyFixture).code).toContain('mode: monitor');
  expect(forSection('policies', configFixture).code).toBeNull();
});
