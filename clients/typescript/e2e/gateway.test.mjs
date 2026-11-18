import assert from 'node:assert/strict';
import { test } from 'node:test';
import { Anthropic } from '@anthropic-ai/sdk';
import { OpenAI } from 'openai';
import { headers, receipt, refusal } from '../dist/index.js';
import { anthropic } from '../dist/anthropic.js';
import { openai } from '../dist/openai.js';

const user = [{ role: 'user', content: 'hi' }];
const runCommand = { type: 'function', function: { name: 'run_command', parameters: {} } };

test('answers a chat', async () => {
  const answer = await openai().chat.completions.create({ model: 'qwen3', messages: user });

  assert.equal(answer.choices[0].message.content, 'Hello from the stub');
});

test('answers a message', async () => {
  const answer = await anthropic().messages.create({
    model: 'claude-sonnet',
    max_tokens: 16,
    messages: user,
  });

  assert.equal(answer.content[0].text, 'Hello from the stub');
});

test('streams a chat', async () => {
  const stream = await openai().chat.completions.create({
    model: 'qwen3',
    messages: user,
    stream: true,
  });

  let text = '';
  for await (const chunk of stream) {
    text += chunk.choices[0]?.delta.content ?? '';
  }
  assert.equal(text, 'Hello from the stub');
});

test('streams a message', async () => {
  const stream = await anthropic().messages.create({
    model: 'claude-sonnet',
    max_tokens: 16,
    messages: user,
    stream: true,
  });

  let text = '';
  for await (const event of stream) {
    if (event.type === 'content_block_delta' && event.delta.type === 'text_delta') {
      text += event.delta.text;
    }
  }
  assert.equal(text, 'Hello from the stub');
});

test('gives a receipt with a trace id and a cost', async () => {
  const { response } = await openai()
    .chat.completions.create({ model: 'qwen3', messages: user })
    .withResponse();

  const given = receipt(response);
  assert.ok(given.traceId);
  assert.ok(given.cost);
});

test('refuses a tool the policy forbids and says why', async () => {
  await assert.rejects(
    openai().chat.completions.create({ model: 'qwen3', messages: user, tools: [runCommand] }),
    (error) => {
      assert.ok(error instanceof OpenAI.PermissionDeniedError);
      const given = refusal(error);
      assert.equal(given.code, 'policy_denied');
      assert.equal(given.status, 403);
      assert.ok(given.traceId);
      return true;
    },
  );
});

test('refuses a spent budget and says why', async () => {
  const client = anthropic({ apiKey: process.env.TOLLGATE_E2E_BROKE_KEY, maxRetries: 0 });

  await assert.rejects(
    client.messages.create({ model: 'claude-sonnet', max_tokens: 16, messages: user }),
    (error) => {
      assert.ok(error instanceof Anthropic.RateLimitError);
      assert.equal(refusal(error).code, 'budget_exceeded');
      return true;
    },
  );
});

test('estimates a dry run', async () => {
  const { data, response } = await anthropic()
    .messages.create(
      { model: 'claude-sonnet', max_tokens: 16, messages: user },
      { headers: headers({ dryRun: true }) },
    )
    .withResponse();

  assert.ok(receipt(response).costMax);
  assert.equal(data.object, 'tollgate.estimate');
});

test('lists the models of each format', async () => {
  const openaiModels = [];
  for await (const model of openai().models.list()) {
    openaiModels.push(model.id);
  }
  const anthropicModels = [];
  for await (const model of anthropic().models.list()) {
    anthropicModels.push(model.id);
  }

  assert.deepEqual(openaiModels, ['qwen3']);
  assert.deepEqual(anthropicModels, ['claude-sonnet']);
});
