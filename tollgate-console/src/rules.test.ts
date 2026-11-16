import { readdirSync, readFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { expect, test } from 'vitest';

type Rule = { name: string; pattern: RegExp; allowedIn?: string };

const rules: Rule[] = [
  { name: 'dangerouslySetInnerHTML', pattern: /dangerouslySetInnerHTML/ },
  { name: 'innerHTML', pattern: /innerHTML/ },
  { name: 'insertAdjacentHTML', pattern: /insertAdjacentHTML/ },
  { name: 'eval(', pattern: /\beval\(/ },
  { name: 'new Function', pattern: /new Function/ },
  { name: 'style="', pattern: /style="/ },
  { name: 'fetch( outside api/client.ts', pattern: /\bfetch\(/, allowedIn: 'api/client.ts' },
  {
    name: 'sessionStorage outside state/session.ts',
    pattern: /sessionStorage/,
    allowedIn: 'state/session.ts',
  },
  {
    name: 'colour outside styles/tokens.css',
    pattern: /#[0-9a-fA-F]{3,8}\b|\brgb\(|\bhsl\(/,
    allowedIn: 'styles/tokens.css',
  },
];

function violations(path: string, text: string): string[] {
  const found: string[] = [];
  text.split('\n').forEach((line, index) => {
    for (const rule of rules) {
      if (rule.allowedIn !== path && rule.pattern.test(line)) {
        found.push(`${path}:${index + 1} ${rule.name}`);
      }
    }
  });
  return found;
}

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      return sourceFiles(full);
    }
    return /\.(ts|tsx|css)$/.test(entry.name) ? [full] : [];
  });
}

test('sourceFollowsTheRules', () => {
  const found = sourceFiles('src')
    .map((full) => ({ path: relative('src', full).split(sep).join('/'), full }))
    .filter((file) => file.path !== 'rules.test.ts')
    .flatMap((file) => violations(file.path, readFileSync(file.full, 'utf8')));
  expect(found).toEqual([]);
});

test('theMatcherCatchesEachRule', () => {
  const broken = [
    'dangerouslySetInnerHTML={x}',
    'el.innerHTML = x',
    'el.insertAdjacentHTML(a, b)',
    'eval(x)',
    'new Function(x)',
    '<p style="color: red">',
    'fetch(url)',
    'sessionStorage.getItem(k)',
    'color: #fff;',
    'color: rgb(0, 0, 0);',
    'color: hsl(0, 0%, 0%);',
  ];
  for (const line of broken) {
    expect(violations('page.tsx', line), line).toHaveLength(1);
  }
  expect(violations('api/client.ts', 'fetch(url)')).toEqual([]);
  expect(violations('state/session.ts', 'sessionStorage.getItem(k)')).toEqual([]);
  expect(violations('styles/tokens.css', 'color: #fff;')).toEqual([]);
});
