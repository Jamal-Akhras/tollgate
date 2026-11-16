import { afterEach, expect, test } from 'vitest';
import { href, navigate, parse, route } from './router';

afterEach(() => {
  location.hash = '';
});

test('anEmptyHashIsTheFlowPage', () => {
  expect(parse('')).toEqual({ name: 'flow', path: 'flow', param: null, query: {} });
  expect(parse('#/').name).toBe('flow');
});

test('parsesEachSimpleRoute', () => {
  for (const name of ['flow', 'activity', 'metrics', 'usage', 'audit', 'config', 'sign-in']) {
    expect(parse(`#/${name}`).name).toBe(name);
  }
});

test('parsesABatchAndARecord', () => {
  expect(parse('#/audit/batches/12')).toEqual({
    name: 'batch',
    path: 'audit/batches/12',
    param: '12',
    query: {},
  });
  const record = parse('#/audit/records/01KA3W.1');
  expect([record.name, record.param]).toEqual(['record', '01KA3W.1']);
});

test('parsesTheQuery', () => {
  const usage = parse('#/usage?by=model&from=2026-11-01');
  expect(usage.name).toBe('usage');
  expect(usage.query).toEqual({ by: 'model', from: '2026-11-01' });
});

test('anUnknownPathIsTheFlowPage', () => {
  expect(parse('#/nowhere').name).toBe('flow');
  expect(parse('#/audit/batches').name).toBe('flow');
  expect(parse('#/usage/extra').name).toBe('flow');
});

test('buildsLinksWithAndWithoutAQuery', () => {
  expect(href('usage')).toBe('#/usage');
  expect(href('usage', { by: 'model' })).toBe('#/usage?by=model');
  expect(href('audit/batches/3')).toBe('#/audit/batches/3');
});

test('followsTheHashWhenItChanges', () => {
  navigate('audit/batches/3', { x: '1' });
  window.dispatchEvent(new Event('hashchange'));
  expect(route.value.name).toBe('batch');
  expect(route.value.param).toBe('3');
  expect(route.value.query).toEqual({ x: '1' });
});
