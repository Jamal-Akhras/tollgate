import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { ApiError, get, post, signedIn, signInWith, signOut } from './client';
import { infoFixture } from './fixtures';

const send = vi.fn();

function answer(status: number, body: unknown) {
  return new Response(typeof body === 'string' ? body : JSON.stringify(body), { status });
}

beforeEach(async () => {
  send.mockReset();
  vi.stubGlobal('fetch', send);
  send.mockResolvedValue(answer(200, infoFixture));
  await signInWith('secret-token');
  send.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

async function failureOf(call: Promise<unknown>): Promise<ApiError> {
  try {
    await call;
  } catch (error) {
    if (error instanceof ApiError) {
      return error;
    }
  }
  throw new Error('expected an ApiError');
}

test('sendsTheTokenToTheAdminPrefix', async () => {
  send.mockResolvedValue(answer(200, infoFixture));
  expect(await get('/info')).toEqual(infoFixture);
  const [url, init] = send.mock.calls[0] ?? [];
  expect(url).toBe('/admin/v1/info');
  expect(init.method).toBe('GET');
  expect(init.headers.Authorization).toBe('Bearer secret-token');
});

test('addsParametersToTheQuery', async () => {
  send.mockResolvedValue(answer(200, []));
  await get('/live', { minutes: 15, by: 'tenant' });
  expect(send.mock.calls[0]?.[0]).toBe('/admin/v1/live?minutes=15&by=tenant');
});

test('postsWithThePostMethod', async () => {
  send.mockResolvedValue(answer(200, { changed: false, version: 'abc' }));
  await post('/config/reload');
  expect(send.mock.calls[0]?.[0]).toBe('/admin/v1/config/reload');
  expect(send.mock.calls[0]?.[1].method).toBe('POST');
});

test('buildsAnApiErrorFromTheErrorBody', async () => {
  send.mockResolvedValue(answer(409, { code: 'audit_off', message: 'The audit trail is off' }));
  const error = await failureOf(get('/audit'));
  expect([error.status, error.code, error.message]).toEqual([
    409,
    'audit_off',
    'The audit trail is off',
  ]);
});

test('usesTheStatusWhenTheBodyIsNotAnAdminError', async () => {
  send.mockResolvedValue(new Response('<html></html>', { status: 502, statusText: 'Bad Gateway' }));
  const error = await failureOf(get('/config'));
  expect([error.status, error.code, error.message]).toEqual([502, '502', 'Bad Gateway']);
});

test('signsOutOnA401', async () => {
  send.mockResolvedValue(answer(401, { code: 'admin_unauthorized', message: 'No' }));
  const error = await failureOf(get('/config'));
  expect(error.status).toBe(401);
  expect(signedIn.value).toBe(false);
});

test('keepsTheSessionWhenATypedTokenIsRefused', async () => {
  send.mockResolvedValue(answer(401, { code: 'admin_unauthorized', message: 'No' }));
  await failureOf(signInWith('typed-token'));
  expect(send.mock.calls[0]?.[1].headers.Authorization).toBe('Bearer typed-token');
  expect(signedIn.value).toBe(true);
});

test('signInWithStoresTheTokenOnlyAfterA200', async () => {
  signOut();
  send.mockResolvedValue(answer(503, { code: 'admin_disabled', message: 'Off' }));
  await failureOf(signInWith('new-token'));
  expect(signedIn.value).toBe(false);
  send.mockResolvedValue(answer(200, infoFixture));
  await signInWith('new-token');
  expect(signedIn.value).toBe(true);
});

test('reportsAnUnreachableApi', async () => {
  send.mockRejectedValue(new TypeError('Failed to fetch'));
  const error = await failureOf(get('/info'));
  expect([error.status, error.code]).toEqual([0, 'unreachable']);
  expect(error.message).toBe(`Can't reach the admin API at ${location.host}`);
});

test('reportsAnAnswerThatIsNotJson', async () => {
  send.mockResolvedValue(answer(200, 'plain text'));
  const error = await failureOf(get('/info'));
  expect(error.code).toBe('invalid_response');
});
