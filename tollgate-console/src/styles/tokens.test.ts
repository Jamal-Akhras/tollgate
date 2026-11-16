import { readFileSync } from 'node:fs';
import { describe, expect, test } from 'vitest';

const css = readFileSync('src/styles/tokens.css', 'utf8');

function block(selector: string): Map<string, string> {
  const start = css.indexOf(`${selector} {`);
  const body = css.slice(start, css.indexOf('}', start));
  const tokens = new Map<string, string>();
  for (const match of body.matchAll(/(--[\w-]+):\s*([^;]+);/g)) {
    tokens.set(match[1] ?? '', (match[2] ?? '').trim());
  }
  return tokens;
}

const themes = { dark: block(':root'), light: block(":root[data-theme='light']") };

function colours(tokens: Map<string, string>): string[] {
  return [...tokens].filter(([, value]) => /^(#|rgba)/.test(value)).map(([name]) => name);
}

function channel(value: number): number {
  const s = value / 255;
  return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
}

function luminance(hex: string): number {
  const n = parseInt(hex.slice(1), 16);
  return (
    0.2126 * channel((n >> 16) & 255) +
    0.7152 * channel((n >> 8) & 255) +
    0.0722 * channel(n & 255)
  );
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return ((hi ?? 0) + 0.05) / ((lo ?? 0) + 0.05);
}

const surfaces = ['--bg', '--surface', '--surface-sunken'];
const text = ['--text', '--text-muted', '--text-subtle', '--accent', '--danger-text', '--info'];
const marks = ['--ok', '--danger'];

describe.each(Object.entries(themes))('%s theme', (_, tokens) => {
  const pairs = (names: string[]) =>
    names.flatMap((name) =>
      surfaces.map((surface) => ({
        label: `${name} on ${surface}`,
        ratio: contrast(tokens.get(name) ?? '', tokens.get(surface) ?? ''),
      })),
    );

  test('textReachesFourPointFive', () => {
    for (const pair of pairs(text)) {
      expect(pair.ratio, pair.label).toBeGreaterThanOrEqual(4.5);
    }
  });

  test('stateColoursReachThree', () => {
    for (const pair of pairs(marks)) {
      expect(pair.ratio, pair.label).toBeGreaterThanOrEqual(3);
    }
  });
});

test('bothThemesDefineTheSameColours', () => {
  expect(colours(themes.light).sort()).toEqual(colours(themes.dark).sort());
});
