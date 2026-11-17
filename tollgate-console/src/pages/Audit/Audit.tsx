import { get } from '../../api/client';
import { usePoll } from '../../api/poll';
import type { AuditStatus } from '../../api/types';
import { href } from '../../router';
import { Banner } from '../../ui/Banner';
import { Card } from '../../ui/Card';
import { Hash } from '../../ui/Hash';
import { PageHeader } from '../../ui/PageHeader';
import { Skeleton } from '../../ui/Skeleton';
import { Time } from '../../ui/Time';
import { auditOffText, isAuditOff } from '../auditOff';
import styles from './Audit.module.css';
import { Chain } from './Chain';
import { FindRecord } from './FindRecord';
import { Verify } from './Verify';

export function Audit() {
  const audit = usePoll(() => get<AuditStatus>('/audit'), 5000);
  const status = audit.data.value;
  const error = audit.error.value;
  const now = Date.now();
  const off = isAuditOff(error);
  const seal = status?.lastSeal ?? null;

  return (
    <div class={styles.page}>
      <PageHeader
        title="Audit"
        note="Checked against the public key in this server's config. Compare it with the copy you keep elsewhere."
        error={off ? null : error}
        updatedAt={audit.updatedAt.value}
        now={now}
      />
      {off && <Banner kind="info">{auditOffText}</Banner>}
      {error && !off && !status && (
        <Banner
          kind="error"
          action={
            <button type="button" class={styles.button} onClick={audit.refresh}>
              Retry
            </button>
          }
        >
          {error.message}
        </Banner>
      )}
      {!status && !error && (
        <div aria-busy="true">
          <Skeleton height="96px" />
        </div>
      )}
      {status && (
        <Card>
          <dl class={styles.facts}>
            <div>
              <dt>Mode</dt>
              <dd>{status.mode}</dd>
            </div>
            <div>
              <dt>Key ID</dt>
              <dd>
                <Hash value={status.keyId} />
              </dd>
            </div>
            <div>
              <dt>Public key</dt>
              <dd>
                <Hash value={status.publicKey} />
              </dd>
            </div>
            <div>
              <dt>Last batch</dt>
              <dd>
                {seal ? (
                  <span class={styles.last}>
                    <a href={href(`audit/batches/${seal.batch}`)}>Batch {seal.batch}</a>
                    <Time iso={seal.time} now={now} />
                    <Hash value={seal.root} />
                  </span>
                ) : (
                  'None yet'
                )}
              </dd>
            </div>
          </dl>
        </Card>
      )}
      {status && <Verify />}
      {status && <FindRecord />}
      {status && <Chain lastBatch={seal?.batch ?? null} />}
    </div>
  );
}
