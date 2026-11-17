import { ApiError } from '../api/client';

export const auditOffText =
  'The audit trail is off in tollgate.yaml, so there is nothing to show here';

export function isAuditOff(error: Error | null): boolean {
  return error instanceof ApiError && error.code === 'audit_off';
}
