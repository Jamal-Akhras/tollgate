import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { configBusyFixture } from '../../api/fixtures';
import { FirstRun, firstRunCommand } from './FirstRun';

afterEach(cleanup);

test('anOpenAiFormatModelUsesTheChatCompletionsEndpoint', () => {
  const command = firstRunCommand('gateway.local', configBusyFixture, 'research')!;
  expect(command).toContain('curl http://gateway.local:8080/v1/chat/completions');
  expect(command).toContain('"model": "qwen3"');
  expect(command).toContain('Authorization: Bearer $TOLLGATE_API_KEY');
});

test('anAnthropicFormatModelUsesTheMessagesEndpointWithItsHeaders', () => {
  const config = {
    ...configBusyFixture,
    tenants: configBusyFixture.tenants.map((tenant) =>
      tenant.name === 'research' ? { ...tenant, models: ['claude-sonnet'] } : tenant,
    ),
  };
  const command = firstRunCommand('localhost', config, 'research')!;
  expect(command).toContain('curl http://localhost:8080/anthropic/v1/messages');
  expect(command).toContain('x-api-key: $TOLLGATE_API_KEY');
  expect(command).toContain('anthropic-version: 2023-06-01');
  expect(command).toContain('"model": "claude-sonnet"');
  expect(command).toContain('"max_tokens"');
});

test('aTenantWithoutAModelHasNoCommand', () => {
  const config = {
    ...configBusyFixture,
    tenants: configBusyFixture.tenants.map((tenant) => ({ ...tenant, models: [] })),
  };
  expect(firstRunCommand('localhost', config, 'research')).toBeNull();
});

test('thePanelShowsTheCommandForTheHostAndTheDefaultPort', () => {
  render(<FirstRun config={configBusyFixture} tenant="research" />);
  expect(screen.getByRole('heading', { name: 'No calls yet. Send one:' })).toBeTruthy();
  expect(screen.getByText(/curl http:\/\/localhost:8080\/v1\/chat\/completions/)).toBeTruthy();
  expect(screen.getByText('8080 is the default gateway port.')).toBeTruthy();
});
