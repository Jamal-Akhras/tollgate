import assert from 'node:assert/strict';
import { test } from 'node:test';
import { receipt, refusal } from '../dist/index.js';
import { openai } from '../dist/openai.js';
import { anthropic } from '../dist/anthropic.js';
import { chat, chatAnswer, message, messageAnswer, withStub } from './stub.mjs';

const calls = {
  openai: (url) =>
    openai({ baseURL: url, apiKey: 'k', maxRetries: 0 }).chat.completions.create(chat),
  anthropic: (url) =>
    anthropic({ baseURL: url, apiKey: 'k', maxRetries: 0 }).messages.create(message),
};

const success = {
  openai: chatAnswer,
  anthropic: messageAnswer,
};

for (const name of Object.keys(calls)) {
  test(`${name}: withResponse gives the receipt`, async () => {
    const headers = {
      'X-Tollgate-Trace-Id': 'trace-1',
      'X-Tollgate-Cost': '0.0412 USD',
      'X-Tollgate-Cost-Max': '0.1 USD',
      'X-Tollgate-Output-Limited': '256',
    };
    await withStub(() => ({ headers, body: success[name] }), async (url) => {
      const { response } = await calls[name](url).withResponse();
      assert.deepEqual(receipt(response), {
        traceId: 'trace-1',
        cost: '0.0412 USD',
        costMax: '0.1 USD',
        outputLimited: 256,
      });
    });
  });

  test(`${name}: a missing or bad header leaves the field out`, async () => {
    for (const bad of ['many', '+7', '-7']) {
      const headers = { 'X-Tollgate-Trace-Id': 'trace-1', 'X-Tollgate-Output-Limited': bad };
      await withStub(() => ({ headers, body: success[name] }), async (url) => {
        const { response } = await calls[name](url).withResponse();
        assert.deepEqual(receipt(response), { traceId: 'trace-1' });
      });
    }
  });

  test(`${name}: a 403 with the header gives the refusal`, async () => {
    const headers = { 'X-Tollgate-Error': 'policy_denied', 'X-Tollgate-Trace-Id': 'trace-2' };
    const reply = () => ({ status: 403, headers, body: { error: { message: 'no' } } });
    await withStub(reply, async (url) => {
      const error = await calls[name](url).catch((caught) => caught);
      const why = refusal(error);
      assert.equal(why.code, 'policy_denied');
      assert.equal(why.status, 403);
      assert.equal(why.traceId, 'trace-2');
      assert.equal(why.retryAfter, undefined);
      assert.equal(why.message, error.message);
    });
  });

  test(`${name}: Retry-After in seconds is read and a date is not`, async () => {
    for (const [value, expected] of [['7', 7], ['+7', undefined], ['-7', undefined], ['Wed, 21 Oct 2026 07:28:00 GMT', undefined]]) {
      const headers = { 'X-Tollgate-Error': 'budget_exceeded', 'Retry-After': value };
      const reply = () => ({ status: 429, headers, body: { error: { message: 'slow' } } });
      await withStub(reply, async (url) => {
        const error = await calls[name](url).catch((caught) => caught);
        assert.equal(refusal(error).retryAfter, expected);
      });
    }
  });

  test(`${name}: a spent budget is not retried`, async () => {
    const headers = { 'X-Tollgate-Error': 'budget_exceeded', 'x-should-retry': 'false' };
    const reply = () => ({ status: 429, headers, body: { error: { message: 'spent' } } });
    await withStub(reply, async (url, requests) => {
      const client = { openai, anthropic }[name]({ baseURL: url, apiKey: 'k' });
      const send = name === 'openai' ? () => client.chat.completions.create(chat) : () => client.messages.create(message);
      await send().catch(() => undefined);
      assert.equal(requests.length, 1);
    });
  });

  test(`${name}: a 500 without the header is no refusal`, async () => {
    await withStub(() => ({ status: 500, body: { error: { message: 'boom' } } }), async (url) => {
      const error = await calls[name](url).catch((caught) => caught);
      assert.equal(refusal(error), undefined);
    });
  });
}

test('things that are not gateway errors are no refusal', () => {
  assert.equal(refusal(new Error('x')), undefined);
  assert.equal(refusal(undefined), undefined);
  assert.equal(refusal('x'), undefined);
  assert.equal(refusal({ status: 403 }), undefined);
});
