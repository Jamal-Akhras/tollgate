function trimmed(value: number): string {
  return value.toFixed(1).replace(/\.0$/, '');
}

export function formatCount(n: number): string {
  if (n < 10_000) {
    return n.toLocaleString('en-US');
  }
  if (n < 1_000_000) {
    return `${trimmed(n / 1000)}k`;
  }
  return `${trimmed(n / 1_000_000)}M`;
}

export function formatDecimal(n: number): string {
  if (n >= 100) {
    return Math.round(n).toLocaleString('en-US');
  }
  return String(Math.round(n * 100) / 100);
}

export function formatBytes(n: number): string {
  if (n < 1024) {
    return `${n} B`;
  }
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = n / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${trimmed(value)} ${units[unit]}`;
}

export function formatMillis(ms: number): string {
  if (ms < 1000) {
    return `${Math.round(ms)} ms`;
  }
  if (ms < 60_000) {
    return `${trimmed(ms / 1000)} s`;
  }
  return `${Math.round(ms / 60_000)} min`;
}

export function formatClock(seconds: number): string {
  return new Date(seconds * 1000).toISOString().slice(11, 19);
}

export function relativeTime(iso: string, now: number): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) {
    return iso;
  }
  const seconds = Math.floor((now - then) / 1000);
  if (seconds < 5) {
    return 'just now';
  }
  if (seconds < 60) {
    return `${seconds} s ago`;
  }
  if (seconds < 3600) {
    return `${Math.floor(seconds / 60)} min ago`;
  }
  if (seconds < 86_400) {
    return `${Math.floor(seconds / 3600)} h ago`;
  }
  return iso.slice(0, 10);
}
