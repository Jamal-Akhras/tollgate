import { expect, test } from 'vitest';
import spec from '../../../docs/api/admin-openapi.json';

type Schema = {
  $ref?: string;
  type?: string;
  enum?: string[];
  required?: string[];
  properties?: Record<string, Schema>;
  additionalProperties?: Schema;
  items?: Schema;
};

type Operation = {
  responses: Record<string, { content?: Record<string, { schema: Schema }> }>;
};

const paths = spec.paths as unknown as Record<string, Record<string, Operation>>;
const schemas = spec.components.schemas as unknown as Record<string, Schema>;

const routes: Record<string, [string, string]> = {
  'activity.busy.json': ['get', '/admin/v1/activity'],
  'activity.json': ['get', '/admin/v1/activity'],
  'audit.busy.json': ['get', '/admin/v1/audit'],
  'audit.json': ['get', '/admin/v1/audit'],
  'batch.json': ['get', '/admin/v1/audit/batches/{batch}'],
  'batches.busy.json': ['get', '/admin/v1/audit/batches'],
  'batches.json': ['get', '/admin/v1/audit/batches'],
  'budgets.busy.json': ['get', '/admin/v1/budgets'],
  'budgets.json': ['get', '/admin/v1/budgets'],
  'change.json': ['put', '/admin/v1/tenants/{tenant}/budget'],
  'config.busy.json': ['get', '/admin/v1/config'],
  'config.json': ['get', '/admin/v1/config'],
  'config.managed.json': ['get', '/admin/v1/config'],
  'config.rewritten.json': ['get', '/admin/v1/config'],
  'created-key.json': ['post', '/admin/v1/tenants/{tenant}/keys'],
  'info.json': ['get', '/admin/v1/info'],
  'live.busy.json': ['get', '/admin/v1/live'],
  'live.json': ['get', '/admin/v1/live'],
  'record.json': ['get', '/admin/v1/audit/records/{id}'],
  'usage.busy.json': ['get', '/admin/v1/usage'],
  'usage.json': ['get', '/admin/v1/usage'],
  'verification.busy.json': ['get', '/admin/v1/audit/verification'],
  'verification.json': ['get', '/admin/v1/audit/verification'],
};

const files = import.meta.glob('./fixtures/*.json', { eager: true, import: 'default' });

function resolve(schema: Schema): Schema {
  if (schema.$ref === undefined) {
    return schema;
  }
  const name = schema.$ref.replace('#/components/schemas/', '');
  return name === 'JsonNode' ? {} : (schemas[name] ?? {});
}

function kind(value: unknown): string {
  if (Array.isArray(value)) {
    return 'array';
  }
  if (Number.isInteger(value)) {
    return 'integer';
  }
  return typeof value;
}

function check(value: unknown, schema: Schema, path: string): string[] {
  const shape = resolve(schema);
  if (value === null || shape.type === undefined) {
    return [];
  }
  const actual = kind(value);
  const fits = actual === shape.type || (actual === 'integer' && shape.type === 'number');
  if (!fits) {
    return [`${path}: ${actual}, not ${shape.type}`];
  }
  if (shape.enum !== undefined && !shape.enum.includes(value as string)) {
    return [`${path}: ${String(value)} is not one of ${shape.enum.join(', ')}`];
  }
  if (Array.isArray(value)) {
    const items = shape.items ?? {};
    return value.flatMap((item, index) => check(item, items, `${path}[${index}]`));
  }
  if (shape.type !== 'object') {
    return [];
  }
  const fields = value as Record<string, unknown>;
  const problems = (shape.required ?? [])
    .filter((name) => !(name in fields))
    .map((name) => `${path}.${name}: missing`);
  if (shape.properties !== undefined) {
    const known = shape.properties;
    for (const [name, field] of Object.entries(fields)) {
      const wanted = known[name];
      if (wanted === undefined) {
        problems.push(`${path}.${name}: not in the API`);
      } else {
        problems.push(...check(field, wanted, `${path}.${name}`));
      }
    }
  } else if (shape.additionalProperties !== undefined) {
    for (const [name, field] of Object.entries(fields)) {
      problems.push(...check(field, shape.additionalProperties, `${path}.${name}`));
    }
  }
  return problems;
}

function answer(method: string, route: string): Schema {
  const responses = paths[route]?.[method]?.responses ?? {};
  const ok = responses['200'] ?? responses['201'];
  return ok?.content?.['application/json']?.schema ?? {};
}

test('everyFixtureHasARoute', () => {
  const found = Object.keys(files).map((file) => file.replace('./fixtures/', ''));
  expect(found.sort()).toEqual(Object.keys(routes).sort());
});

for (const [file, [method, route]] of Object.entries(routes)) {
  test(`${file.replace('.json', '')}MatchesTheAnswerOf${method.toUpperCase()}${route}`, () => {
    const fixture = files[`./fixtures/${file}`];
    expect(check(fixture, answer(method, route), file)).toEqual([]);
  });
}

test('aFixtureWithAFieldTheApiDoesNotHaveFails', () => {
  const wrong = { version: 'a', previous: 'b', changed: true, extra: 1 };
  expect(check(wrong, answer('put', '/admin/v1/tenants/{tenant}/budget'), 'wrong')).toEqual([
    'wrong.extra: not in the API',
  ]);
});

test('aFixtureWithTheWrongTypeFails', () => {
  const wrong = { version: 'a', previous: 'b', changed: 'yes' };
  expect(check(wrong, answer('put', '/admin/v1/tenants/{tenant}/budget'), 'wrong')).toEqual([
    'wrong.changed: string, not boolean',
  ]);
});
