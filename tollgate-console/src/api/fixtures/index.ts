import type {
  AdminInfo,
  AuditStatus,
  BatchDetail,
  BatchView,
  BudgetState,
  CallView,
  ConfigChange,
  ConfigView,
  CreatedKey,
  LiveSnapshot,
  RecordProofs,
  UsageReport,
  Verification,
} from '../types';
import activity from './activity.json';
import activityBusy from './activity.busy.json';
import audit from './audit.json';
import auditBusy from './audit.busy.json';
import batch from './batch.json';
import batches from './batches.json';
import batchesBusy from './batches.busy.json';
import budgets from './budgets.json';
import budgetsBusy from './budgets.busy.json';
import change from './change.json';
import config from './config.json';
import configBusy from './config.busy.json';
import createdKey from './created-key.json';
import info from './info.json';
import live from './live.json';
import liveBusy from './live.busy.json';
import record from './record.json';
import usage from './usage.json';
import usageBusy from './usage.busy.json';
import verification from './verification.json';
import verificationBusy from './verification.busy.json';

type Defined<T> = T extends object ? { [K in keyof T]-?: Defined<Exclude<T[K], undefined>> } : T;

function shaped<T>(json: T): Defined<T> {
  return json as unknown as Defined<T>;
}

export const infoFixture: AdminInfo = shaped(info);
export const configFixture: ConfigView = shaped({ ...config, edits: 'read-only' as const });
export const configBusyFixture: ConfigView = shaped({ ...configBusy, edits: 'read-only' as const });
export const configManagedFixture: ConfigView = { ...configBusyFixture, edits: 'managed' };
export const configRewrittenFixture: ConfigView = { ...configManagedFixture, rewritten: true };
export const changeFixture: ConfigChange = shaped(change);
export const createdKeyFixture: CreatedKey = shaped(createdKey);
export const liveFixture: LiveSnapshot = shaped(live);
export const liveBusyFixture: LiveSnapshot = shaped(liveBusy);
export const activityFixture: CallView[] = shaped(activity);
export const activityBusyFixture: CallView[] = shaped(activityBusy);
export const budgetsFixture: BudgetState[] = shaped(budgets);
export const budgetsBusyFixture: BudgetState[] = shaped(budgetsBusy);
export const usageFixture: UsageReport = shaped(usage);
export const usageBusyFixture: UsageReport = shaped(usageBusy);
export const auditFixture: AuditStatus = shaped(audit);
export const auditBusyFixture: AuditStatus = shaped(auditBusy);
export const batchesFixture: BatchView[] = shaped(batches);
export const batchesBusyFixture: BatchView[] = shaped(batchesBusy);
export const batchFixture: BatchDetail = shaped(batch);
export const recordFixture: RecordProofs = shaped(record);
export const verificationFixture: Verification = shaped(verification);
export const verificationBusyFixture: Verification = shaped(verificationBusy);
