import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const root = new URL('..', import.meta.url);
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';
const manifest = JSON.parse(readFileSync(new URL('package.json', root), 'utf8'));

test('the package holds exactly its files', () => {
  const out = execFileSync(npm, ['pack', '--dry-run', '--json', '--ignore-scripts'], {
    cwd: root,
    encoding: 'utf8',
    shell: process.platform === 'win32',
  });
  const files = JSON.parse(out)[0].files.map((file) => file.path).sort();
  assert.deepEqual(files, [
    'LICENSE',
    'README.md',
    'dist/anthropic.d.ts',
    'dist/anthropic.js',
    'dist/index.d.ts',
    'dist/index.js',
    'dist/openai.d.ts',
    'dist/openai.js',
    'dist/settings.d.ts',
    'dist/settings.js',
    'package.json',
  ]);
});

test('the package has no dependencies and no install-time scripts', () => {
  assert.equal(manifest.dependencies, undefined);
  for (const name of ['install', 'preinstall', 'postinstall', 'prepare']) {
    assert.equal(manifest.scripts[name], undefined);
  }
});

test('the version is the root pom version', () => {
  const pom = readFileSync(new URL('../../pom.xml', root), 'utf8');
  const version = /<version>([^<]+)<\/version>/.exec(pom)[1].replace('-SNAPSHOT', '');
  assert.equal(manifest.version, version);
});
