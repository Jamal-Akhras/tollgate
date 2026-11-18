import { useEffect, useRef, useState } from 'preact/hooks';
import { ApiError, get } from '../../api/client';
import type { ProvedRecord, RecordProofs } from '../../api/types';
import { href, route } from '../../router';
import { Banner } from '../../ui/Banner';
import buttons from '../../ui/button.module.css';
import { Card } from '../../ui/Card';
import { CodeBlock } from '../../ui/CodeBlock';
import { EmptyState } from '../../ui/EmptyState';
import { PageHeader } from '../../ui/PageHeader';
import { RetryBanner } from '../../ui/RetryBanner';
import { Segmented } from '../../ui/Segmented';
import { auditOffText, isAuditOff } from '../auditOff';
import { noRecordText } from './FindRecord';
import { ProofSteps } from './ProofSteps';
import styles from './Record.module.css';
import { useLoaded } from './useLoaded';
import { BackToAudit, Waiting } from './Waiting';

const unsealedRetryMillis = 2000;
const unsealedRetries = 5;

const notSealedText = 'Not sealed yet. Batches close within a second';

function Proved({ proved }: { proved: ProvedRecord }) {
  const { proof, seal } = proved;
  return (
    <>
      <CodeBlock code={JSON.stringify(proved.record, null, 2)} />
      {proved.sealed && proof ? (
        <Card>
          <h2 class={styles.heading}>Proof</h2>
          {seal && (
            <p class={styles.where}>
              <a href={href(`audit/batches/${seal.batch}`, { leaf: String(proof.index) })}>
                Leaf {proof.index} of {proof.size} in batch {seal.batch}
              </a>
            </p>
          )}
          <ProofSteps proof={proof} seal={seal} signatureValid={proved.signatureValid} />
        </Card>
      ) : null}
    </>
  );
}

function RecordPage({ id }: { id: string }) {
  const { data, error, busy, retry } = useLoaded(
    () => get<RecordProofs>(`/audit/records/${encodeURIComponent(id)}`),
    id,
  );
  const [tab, setTab] = useState<'intent' | 'outcome'>('intent');
  const tries = useRef(0);
  const off = isAuditOff(error);
  const missing = error instanceof ApiError && error.status === 404;
  const failed = error && !off && !missing;
  const both = data?.intent && data.outcome;
  const shown = data ? (both ? data[tab] : (data.intent ?? data.outcome)) : null;
  const waiting = [data?.intent, data?.outcome].some(
    (proved) => proved && !(proved.sealed && proved.proof),
  );
  const retrying = waiting && tries.current < unsealedRetries;

  useEffect(() => {
    if (!retrying) {
      return undefined;
    }
    const timer = setTimeout(() => {
      tries.current += 1;
      retry();
    }, unsealedRetryMillis);
    return () => clearTimeout(timer);
  }, [data]);

  function checkAgain() {
    tries.current = 0;
    retry();
  }

  return (
    <div class={styles.page}>
      <BackToAudit />
      <PageHeader title="Audit record" note={id} />
      {off && <Banner kind="info">{auditOffText}</Banner>}
      {missing && (
        <EmptyState title={noRecordText} text={`The audit trail has nothing under ${id}.`} />
      )}
      {failed && <RetryBanner error={error} onRetry={retry} />}
      {!data && !error && <Waiting height="200px" busy={busy} />}
      {data && both && (
        <Segmented
          label="Record"
          options={[
            { value: 'intent', label: 'Intent' },
            { value: 'outcome', label: 'Outcome' },
          ]}
          value={tab}
          onChange={(value) => setTab(value === 'outcome' ? 'outcome' : 'intent')}
        />
      )}
      {shown && <Proved key={tab} proved={shown} />}
      {waiting && (
        <Banner
          kind="info"
          action={
            tries.current >= unsealedRetries ? (
              <button type="button" class={buttons.button} onClick={checkAgain}>
                Check again
              </button>
            ) : undefined
          }
        >
          {notSealedText}
        </Banner>
      )}
    </div>
  );
}

export function AuditRecord() {
  const id = route.value.param ?? '';
  return <RecordPage key={id} id={id} />;
}
