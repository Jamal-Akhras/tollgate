import { act, cleanup, renderHook } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { ApiError } from './client';
import { usePoll } from './poll';

let hidden = false;

function setHidden(value: boolean) {
  hidden = value;
  document.dispatchEvent(new Event('visibilitychange'));
}

async function tick(millis: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(millis);
  });
}

beforeEach(() => {
  hidden = false;
  vi.useFakeTimers();
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

test('loadsAtOnceThenAfterEachInterval', async () => {
  const load = vi.fn().mockResolvedValue('one');
  const { result } = renderHook(() => usePoll(load, 1000));
  await tick(0);
  expect(load).toHaveBeenCalledTimes(1);
  expect(result.current.data.value).toBe('one');
  expect(result.current.updatedAt.value).not.toBeNull();
  await tick(999);
  expect(load).toHaveBeenCalledTimes(1);
  await tick(1);
  expect(load).toHaveBeenCalledTimes(2);
  await tick(1000);
  expect(load).toHaveBeenCalledTimes(3);
});

test('waitsForTheAnswerBeforeCountingTheInterval', async () => {
  let finish: (value: string) => void = () => undefined;
  const load = vi.fn().mockImplementation(() => new Promise<string>((done) => (finish = done)));
  const { result } = renderHook(() => usePoll(load, 1000));
  await tick(5000);
  expect(load).toHaveBeenCalledTimes(1);
  expect(result.current.loading.value).toBe(true);
  finish('slow');
  await tick(0);
  expect(result.current.loading.value).toBe(false);
  await tick(999);
  expect(load).toHaveBeenCalledTimes(1);
  await tick(1);
  expect(load).toHaveBeenCalledTimes(2);
});

test('stopsWhileTheTabIsHiddenAndReloadsWhenItShows', async () => {
  const load = vi.fn().mockResolvedValue('x');
  renderHook(() => usePoll(load, 1000));
  await tick(0);
  setHidden(true);
  await tick(5000);
  expect(load).toHaveBeenCalledTimes(1);
  await act(async () => setHidden(false));
  await tick(0);
  expect(load).toHaveBeenCalledTimes(2);
});

test('keepsTheLastDataWhenALoadFails', async () => {
  const load = vi.fn().mockResolvedValueOnce('good').mockRejectedValue(new Error('down'));
  const { result } = renderHook(() => usePoll(load, 1000));
  await tick(0);
  const updated = result.current.updatedAt.value;
  await tick(1000);
  expect(result.current.data.value).toBe('good');
  expect(result.current.error.value?.message).toBe('down');
  expect(result.current.updatedAt.value).toBe(updated);
  await tick(1000);
  expect(load).toHaveBeenCalledTimes(3);
});

test('clearsTheErrorWhenALoadSucceeds', async () => {
  const load = vi.fn().mockRejectedValueOnce(new Error('down')).mockResolvedValue('back');
  const { result } = renderHook(() => usePoll(load, 1000));
  await tick(0);
  expect(result.current.error.value).not.toBeNull();
  await tick(1000);
  expect(result.current.error.value).toBeNull();
  expect(result.current.data.value).toBe('back');
});

test('retriesABusyAnswerAfterTwoSecondsWithoutReplacingTheData', async () => {
  const busy = new ApiError(429, 'audit_busy', 'Busy');
  const load = vi
    .fn()
    .mockResolvedValueOnce('good')
    .mockRejectedValueOnce(busy)
    .mockResolvedValue('next');
  const { result } = renderHook(() => usePoll(load, 60_000));
  await tick(0);
  await tick(60_000);
  expect(load).toHaveBeenCalledTimes(2);
  expect(result.current.data.value).toBe('good');
  expect(result.current.error.value).toBeNull();
  await tick(1999);
  expect(load).toHaveBeenCalledTimes(2);
  await tick(1);
  expect(load).toHaveBeenCalledTimes(3);
  expect(result.current.data.value).toBe('next');
});

test('refreshLoadsRightAway', async () => {
  const load = vi.fn().mockResolvedValue('x');
  const { result } = renderHook(() => usePoll(load, 60_000));
  await tick(0);
  await act(async () => result.current.refresh());
  await tick(0);
  expect(load).toHaveBeenCalledTimes(2);
});

test('stopsWhenTheComponentUnmounts', async () => {
  const load = vi.fn().mockResolvedValue('x');
  const { unmount } = renderHook(() => usePoll(load, 1000));
  await tick(0);
  unmount();
  await tick(5000);
  expect(load).toHaveBeenCalledTimes(1);
});

test('skipsALoadWhilePausedAndLoadsAgainWhenResumed', async () => {
  let paused = true;
  const load = vi.fn().mockResolvedValue('one');
  renderHook(() => usePoll(load, 1000, () => paused));
  await tick(0);
  await tick(3000);
  expect(load).not.toHaveBeenCalled();
  paused = false;
  await tick(1000);
  expect(load).toHaveBeenCalledTimes(1);
});
