export type AdminInfo = { service: string; version: string };
export type ReloadResult = { changed: boolean; version: string };
export type AdminError = { code: string; message: string };

export type BudgetState = {
  tenant: string;
  limit: string;
  period: string;
  currency: string;
  spent: string;
  reserved: string;
  available: string;
};

export type Tokens = { input: number; output: number; cacheRead: number; cacheWrite: number };

export type Row = {
  key: string;
  calls: number;
  unfinished: number;
  tokens: Tokens;
  cost: Record<string, string>;
  refused: Record<string, number>;
};

export type UsageReport = { by: string; from: string; to: string; rows: Row[] };

export type ProviderView = {
  name: string;
  format: string;
  host: string;
  region: string | null;
  concurrent: number | null;
  apiKey: string | null;
};

export type ModelView = {
  name: string;
  provider: string;
  upstream: string;
  free: boolean;
  outputCap: number | null;
  fallback: string[];
};

export type BudgetView = { limit: string; currency: string; period: string };

export type LimitsView = {
  requestsPerMinute: number | null;
  tokensPerMinute: number | null;
  concurrent: number | null;
};

export type TenantView = {
  name: string;
  dataClass: string | null;
  policy: string;
  models: string[];
  budget: BudgetView | null;
  limits: LimitsView;
  attributesUsers: boolean;
  showsCosts: boolean;
};

export type KeyView = { id: string; tenant: string };

export type GuardView = {
  type: string;
  mode: string;
  maxBytes: number | null;
  regions: Record<string, string[]> | null;
  allow: string[] | null;
  refuse: string[] | null;
};

export type PolicyView = {
  name: string;
  guards: GuardView[];
  features: Record<string, string[]>;
  redact: Record<string, string[]>;
  refuse: Record<string, string[]>;
};

export type AuditView = { mode: string; publicKey: string | null };

export type ConfigView = {
  version: string;
  edits: string;
  dataClasses: string[];
  providers: ProviderView[];
  models: ModelView[];
  tenants: TenantView[];
  keys: KeyView[];
  policies: PolicyView[];
  audit: AuditView;
};

export type SealView = {
  batch: number;
  records: number;
  time: string;
  root: string;
  previous: string;
  key: string;
  hmacKey: string;
};

export type Bucket = {
  start: number;
  sent: number;
  refused: number;
  failed: number;
  inputTokens: number;
  outputTokens: number;
  cost: Record<string, string>;
  p50Millis: number | null;
  p95Millis: number | null;
};

export type Flow = {
  tenant: string;
  model: string;
  provider: string;
  sent: number;
  refused: Record<string, number>;
  failed: number;
  fallbacks: number;
  inputTokens: number;
  outputTokens: number;
  cost: Record<string, string>;
  redacted: number;
  lastSent: string | null;
  lastRefused: string | null;
  lastFallback: string | null;
};

export type GuardCount = {
  tenant: string;
  guard: string;
  allowed: number;
  refused: number;
  monitored: number;
  lastRefused: string | null;
};

export type LiveSnapshot = {
  now: string;
  since: string;
  skipped: boolean;
  bucketSeconds: number;
  buckets: Bucket[];
  p50Millis: number | null;
  p95Millis: number | null;
  flows: Flow[];
  guards: GuardCount[];
  inFlight: Record<string, number>;
  limited: Record<string, number>;
  lastSeal: SealView | null;
};

export type Amount = { amount: string; currency: string };

export type StepView = {
  guard: string;
  monitored: boolean;
  allowed: boolean;
  code: string | null;
  message: string | null;
  micros: number;
};

export type OutcomeView = {
  time: string;
  result: string;
  status: number | null;
  tokens: Tokens | null;
  cost: Amount;
  millis: number;
};

export type CallView = {
  id: string;
  time: string;
  tenant: string;
  key: string;
  endpoint: string;
  model: string;
  provider: string;
  upstream: string;
  dataClass: string | null;
  attributed: boolean;
  fallback: boolean;
  steps: StepView[];
  allowed: boolean;
  code: string | null;
  message: string | null;
  reservation: Amount | null;
  redacted: Record<string, number>;
  outcome: OutcomeView | null;
};

export type AuditStatus = {
  mode: string;
  publicKey: string;
  keyId: string;
  files: number;
  lastSeal: SealView | null;
};

export type BatchView = { seal: SealView; file: string };

export type LeafView = {
  index: number;
  type: string;
  id: string;
  time: string;
  leafHash: string;
};

export type BatchDetail = {
  seal: SealView;
  signature: string;
  file: string;
  signatureValid: boolean;
  rootMatches: boolean;
  leaves: LeafView[];
};

export type ProofView = {
  index: number;
  size: number;
  encoding: string;
  leafHash: string;
  path: string[];
  root: string;
};

export type ProvedRecord = {
  record: Record<string, unknown>;
  sealed: boolean;
  proof: ProofView | null;
  seal: SealView | null;
  signatureValid: boolean | null;
};

export type RecordProofs = { intent: ProvedRecord | null; outcome: ProvedRecord | null };

export type ProblemView = { file: string; line: number; message: string };

export type Verification = {
  intact: boolean;
  records: number;
  batches: number;
  failures: ProblemView[];
  warnings: ProblemView[];
  lastSeal: SealView | null;
  millis: number;
};
