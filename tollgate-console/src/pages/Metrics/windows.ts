export const windows = [5, 15, 60];
export const defaultWindow = 15;

export function windowFrom(query: string | undefined): number {
  const minutes = Number(query);
  return windows.includes(minutes) ? minutes : defaultWindow;
}

export function stepFor(minutes: number): number {
  return minutes <= 5 ? 15 : 60;
}

export function before(minutes: number): string {
  return `the ${minutes} minutes before`;
}
