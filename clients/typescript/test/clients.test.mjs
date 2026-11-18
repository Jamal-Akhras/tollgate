import assert from 'node:assert/strict';
import { test } from 'node:test';
import { DATA_CLASS, DRY_RUN, USER, headers } from '../dist/index.js';
import { openai } from '../dist/openai.js';
import { anthropic } from '../dist/anthropic.js';
import { chat, chatAnswer, message, messageAnswer, withStub } from './stub.mjs';

const answer = (path) => ({ body: path.includes('messages') ? messageAnswer : chatAnswer });

async function withEnv(values, run) {
  const saved = {};
  for (const name of Object.keys(values)) {
    saved[name] = process.env[name];
    if (values[name] === undefined) delete process.env[name];
    else process.env[name] = values[name];
  }
  try {
    return await run();
  } finally {
    for (const name of Object.keys(saved)) {
      if (saved[name] === undefined) delete process.env[name];
      else process.env[name] = saved[name];
    }
  }
}

test('openai reaches /v1/chat/completions with the key as a bearer', async () => {
  await withStub(answer, async (url, requests) => {
    await openai({ baseURL: url, apiKey: 'tg-key', maxRetries: 0 }).chat.completions.create(chat);
    assert.equal(requests[0].path, '/v1/chat/completions');
    assert.equal(requests[0].headers.authorization, 'Bearer tg-key');
  });
});

test('anthropic reaches /anthropic/v1/messages with the key', async () => {
  await withStub(answer, async (url, requests) => {
    await anthropic({ baseURL: url, apiKey: 'tg-key', maxRetries: 0 }).messages.create(message);
    assert.equal(requests[0].path, '/anthropic/v1/messages');
    assert.equal(requests[0].headers.authorization, 'Bearer tg-key');
    assert.equal(requests[0].headers['x-api-key'], undefined);
  });
});

test('dataClass and user arrive on every call and the caller defaultHeaders win', async () => {
  await withStub(answer, async (url, requests) => {
    const base = { baseURL: url, apiKey: 'k', maxRetries: 0, dataClass: 'internal', user: 'ann' };
    await openai(base).chat.completions.create(chat);
    await anthropic(base).messages.create(message);
    const own = { ...base, defaultHeaders: { 'x-tollgate-user': 'bob' } };
    await openai(own).chat.completions.create(chat);
    await anthropic(own).messages.create(message);
    for (const request of requests.slice(0, 2)) {
      assert.equal(request.headers['x-tollgate-data-class'], 'internal');
      assert.equal(request.headers['x-tollgate-user'], 'ann');
    }
    for (const request of requests.slice(2)) {
      assert.equal(request.headers['x-tollgate-data-class'], 'internal');
      assert.equal(request.headers['x-tollgate-user'], 'bob');
    }
  });
});

test('headers(dryRun) passed per call arrives', async () => {
  await withStub(answer, async (url, requests) => {
    const client = openai({ baseURL: url, apiKey: 'k', maxRetries: 0 });
    await client.chat.completions.create(chat, { headers: headers({ dryRun: true }) });
    assert.equal(requests[0].headers['x-tollgate-dry-run'], 'true');
  });
});

test('headers holds only what was asked for', () => {
  assert.deepEqual(headers({}), {});
  assert.deepEqual(headers({ dryRun: false }), {});
  assert.deepEqual(headers({ dataClass: '', user: '' }), {});
  assert.deepEqual(headers({ dataClass: 'pii', user: 'ann', dryRun: true }), {
    [DATA_CLASS]: 'pii',
    [USER]: 'ann',
    [DRY_RUN]: 'true',
  });
});

test('the address comes from the argument, then the variable, then the default', async () => {
  await withStub(answer, async (argUrl, argRequests) => {
    await withStub(answer, async (envUrl, envRequests) => {
      const env = { TOLLGATE_BASE_URL: `${envUrl}/`, TOLLGATE_API_KEY: 'env-key' };
      await withEnv(env, async () => {
        await openai({ baseURL: `${argUrl}/`, maxRetries: 0 }).chat.completions.create(chat);
        await openai({ maxRetries: 0 }).chat.completions.create(chat);
        await anthropic({ maxRetries: 0 }).messages.create(message);
      });
      assert.deepEqual(
        argRequests.map((request) => request.path),
        ['/v1/chat/completions'],
      );
      assert.equal(argRequests[0].headers.authorization, 'Bearer env-key');
      assert.deepEqual(
        envRequests.map((request) => request.path),
        ['/v1/chat/completions', '/anthropic/v1/messages'],
      );
    });
  });
  await withEnv({ TOLLGATE_BASE_URL: undefined, TOLLGATE_API_KEY: 'k' }, async () => {
    assert.equal(openai().baseURL, 'http://localhost:8080/v1');
    assert.equal(anthropic().baseURL, 'http://localhost:8080/anthropic');
  });
});

test('vendor variables are never used', async () => {
  await withStub(answer, async (url, requests) => {
    const env = {
      OPENAI_API_KEY: 'openai-key',
      ANTHROPIC_API_KEY: 'anthropic-key',
      OPENAI_BASE_URL: 'http://127.0.0.1:1',
      ANTHROPIC_BASE_URL: 'http://127.0.0.1:1',
      TOLLGATE_API_KEY: 'tg-key',
    };
    await withEnv(env, async () => {
      await openai({ baseURL: url, maxRetries: 0 }).chat.completions.create(chat);
      await anthropic({ baseURL: url, maxRetries: 0 }).messages.create(message);
    });
    assert.equal(requests[0].headers.authorization, 'Bearer tg-key');
    assert.equal(requests[1].headers.authorization, 'Bearer tg-key');
  });
});

test('a missing key throws and the message holds no key', async () => {
  const env = {
    TOLLGATE_API_KEY: undefined,
    OPENAI_API_KEY: 'openai-key',
    ANTHROPIC_API_KEY: 'anthropic-key',
  };
  await withEnv(env, async () => {
    for (const make of [openai, anthropic]) {
      assert.throws(make, { message: 'Set TOLLGATE_API_KEY or pass an API key' });
    }
  });
});
