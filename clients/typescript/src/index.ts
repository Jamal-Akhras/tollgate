export const DATA_CLASS = 'X-Tollgate-Data-Class';
export const USER = 'X-Tollgate-User';
export const DRY_RUN = 'X-Tollgate-Dry-Run';

export function headers(options: {
  dataClass?: string;
  user?: string;
  dryRun?: boolean;
}): Record<string, string> {
  const result: Record<string, string> = {};
  if (options.dataClass) {
    result[DATA_CLASS] = options.dataClass;
  }
  if (options.user) {
    result[USER] = options.user;
  }
  if (options.dryRun) {
    result[DRY_RUN] = 'true';
  }
  return result;
}

export interface Receipt {
  traceId?: string;
  cost?: string;
  costMax?: string;
  outputLimited?: number;
}

export function receipt(response: { headers: Headers }): Receipt {
  const result: Receipt = {};
  const traceId = response.headers.get('X-Tollgate-Trace-Id');
  const cost = response.headers.get('X-Tollgate-Cost');
  const costMax = response.headers.get('X-Tollgate-Cost-Max');
  const outputLimited = whole(response.headers.get('X-Tollgate-Output-Limited'));
  if (traceId !== null) {
    result.traceId = traceId;
  }
  if (cost !== null) {
    result.cost = cost;
  }
  if (costMax !== null) {
    result.costMax = costMax;
  }
  if (outputLimited !== undefined) {
    result.outputLimited = outputLimited;
  }
  return result;
}

export interface Refusal {
  code: string;
  message: string;
  status: number;
  traceId?: string;
  retryAfter?: number;
}

export function refusal(error: unknown): Refusal | undefined {
  if (typeof error !== 'object' || error === null) {
    return undefined;
  }
  const { status, headers, message } = error as {
    status?: unknown;
    headers?: unknown;
    message?: unknown;
  };
  if (typeof status !== 'number' || !(headers instanceof Headers)) {
    return undefined;
  }
  const code = headers.get('X-Tollgate-Error');
  if (code === null) {
    return undefined;
  }
  const result: Refusal = {
    code,
    message: typeof message === 'string' ? message : '',
    status,
  };
  const traceId = headers.get('X-Tollgate-Trace-Id');
  const retryAfter = whole(headers.get('Retry-After'));
  if (traceId !== null) {
    result.traceId = traceId;
  }
  if (retryAfter !== undefined) {
    result.retryAfter = retryAfter;
  }
  return result;
}

function whole(value: string | null): number | undefined {
  return value !== null && /^\d+$/.test(value) ? Number(value) : undefined;
}
