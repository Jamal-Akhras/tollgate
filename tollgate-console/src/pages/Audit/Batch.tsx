import { useEffect, useState } from 'preact/hooks';
import { ApiError, get } from '../../api/client';
import type { BatchDetail, LeafView } from '../../api/types';
import { canHash } from '../../merkle/proof';
import { levels, shape, type TreeNode } from '../../merkle/tree';
import { navigate, route } from '../../router';
import { Banner } from '../../ui/Banner';
import buttons from '../../ui/button.module.css';
import { Card } from '../../ui/Card';
import { EmptyState } from '../../ui/EmptyState';
import { Hash } from '../../ui/Hash';
import { MerkleTree } from '../../ui/MerkleTree';
import { PageHeader } from '../../ui/PageHeader';
import { RetryBanner } from '../../ui/RetryBanner';
import { Time } from '../../ui/Time';
import { auditOffText, isAuditOff } from '../auditOff';
import styles from './Batch.module.css';
import { useLoaded } from './useLoaded';
import { BackToAudit, Waiting } from './Waiting';

type SealedProps = {
  batch: BatchDetail;
  selected: number | undefined;
  onChoose: (leaf: LeafView) => void;
};

export function Sealed({ batch, selected, onChoose }: SealedProps) {
  const [nodes, setNodes] = useState<TreeNode[] | null>(null);
  const hashes = canHash();
  const leafHashes = batch.leaves.map((leaf) => leaf.leafHash);

  useEffect(() => {
    let stopped = false;
    if (hashes) {
      void levels(leafHashes).then((built) => !stopped && setNodes(built));
    } else {
      setNodes(shape(leafHashes));
    }
    return () => {
      stopped = true;
    };
  }, [batch, hashes]);

  const top = nodes?.at(-1);
  const recomputed = hashes && top ? top.hash : null;
  const now = Date.now();

  return (
    <>
      <Card>
        <dl class={styles.facts}>
          <div>
            <dt>Sealed</dt>
            <dd>
              <Time iso={batch.seal.time} now={now} />
            </dd>
          </div>
          <div>
            <dt>Records</dt>
            <dd>{batch.seal.records}</dd>
          </div>
          <div>
            <dt>Root</dt>
            <dd>
              <Hash value={batch.seal.root} />
            </dd>
          </div>
          <div>
            <dt>Previous root</dt>
            <dd>
              <Hash value={batch.seal.previous} />
            </dd>
          </div>
          <div>
            <dt>Signed by key</dt>
            <dd>
              <Hash value={batch.seal.key} />
            </dd>
          </div>
        </dl>
        <p class={styles.verdicts}>
          <span class={batch.signatureValid ? styles.good : styles.bad}>
            {batch.signatureValid ? 'Signature valid' : 'Signature not valid'}
          </span>
          <span class={batch.rootMatches ? styles.good : styles.bad}>
            {batch.rootMatches ? 'Root matches' : 'Root does not match'}
          </span>
          <span class={styles.source}>as the server reports them</span>
        </p>
        {hashes && recomputed !== null && (
          <p class={styles.verdicts}>
            <span class={styles.source}>Recomputed in your browser</span>
            <Hash value={recomputed} />
            <span class={recomputed === batch.seal.root ? styles.good : styles.bad}>
              {recomputed === batch.seal.root ? 'Root matches' : 'Root does not match'}
            </span>
          </p>
        )}
        {!hashes && (
          <p class={styles.source}>
            Your browser can't hash here (it needs HTTPS or localhost). These are the server's
            results.
          </p>
        )}
      </Card>
      {nodes && (
        <MerkleTree
          leaves={batch.leaves}
          nodes={nodes}
          selected={
            selected !== undefined && selected >= 0 && selected < batch.leaves.length
              ? selected
              : undefined
          }
          onChoose={onChoose}
        />
      )}
    </>
  );
}

function BatchPage({ number }: { number: string }) {
  const { data, error, busy, retry } = useLoaded(
    () => get<BatchDetail>(`/audit/batches/${number}`),
    number,
  );
  const off = isAuditOff(error);
  const missing = error instanceof ApiError && error.status === 404;
  const failed = error && !off && !missing;
  const asked = route.value.query.leaf;
  const selected = asked && Number.isInteger(Number(asked)) ? Number(asked) : undefined;

  return (
    <div class={styles.page}>
      <BackToAudit />
      <PageHeader title={`Batch ${number}`} note={data ? `In ${data.file}` : undefined} />
      {off && <Banner kind="info">{auditOffText}</Banner>}
      {missing && (
        <EmptyState title="No such batch" text={`The audit trail has no sealed batch ${number}.`} />
      )}
      {failed && <RetryBanner error={error} onRetry={retry} />}
      {!data && !error && <Waiting height="200px" busy={busy} />}
      {data && (
        <Sealed
          batch={data}
          selected={selected}
          onChoose={(leaf) => navigate(`audit/records/${leaf.id}`)}
        />
      )}
    </div>
  );
}

export function Batch() {
  const number = route.value.param ?? '';
  return <BatchPage key={number} number={number} />;
}
