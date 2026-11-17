import { useEffect, useState } from 'preact/hooks';
import { ApiError, get } from '../../api/client';
import type { BatchDetail } from '../../api/types';
import { canHash } from '../../merkle/proof';
import { levels, shape, type TreeNode } from '../../merkle/tree';
import { href, navigate, route } from '../../router';
import { Banner } from '../../ui/Banner';
import { Card } from '../../ui/Card';
import { EmptyState } from '../../ui/EmptyState';
import { Hash } from '../../ui/Hash';
import { MerkleTree } from '../../ui/MerkleTree';
import { PageHeader } from '../../ui/PageHeader';
import { Skeleton } from '../../ui/Skeleton';
import { Time } from '../../ui/Time';
import { auditOffText, isAuditOff } from '../auditOff';
import styles from './Batch.module.css';
import { useLoaded } from './useLoaded';

function Sealed({ batch }: { batch: BatchDetail }) {
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

  const top = nodes && nodes.length > 0 ? nodes[nodes.length - 1] : undefined;
  const recomputed = hashes && top ? top.hash : null;
  const selected = Number(route.value.query.leaf ?? '');
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
            Number.isInteger(selected) && selected >= 0 && selected < batch.leaves.length
              ? selected
              : undefined
          }
          onChoose={(leaf) => navigate(`audit/records/${leaf.id}`)}
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

  return (
    <div class={styles.page}>
      <a class={styles.back} href={href('audit')}>
        ← Audit
      </a>
      <PageHeader title={`Batch ${number}`} note={data ? `In ${data.file}` : undefined} />
      {off && <Banner kind="info">{auditOffText}</Banner>}
      {missing && (
        <EmptyState title="No such batch" text={`The audit trail has no sealed batch ${number}.`} />
      )}
      {failed && (
        <Banner
          kind="error"
          action={
            <button type="button" class={styles.button} onClick={retry}>
              Retry
            </button>
          }
        >
          {error.message}
        </Banner>
      )}
      {!data && !error && (
        <div aria-busy="true">
          <Skeleton height="200px" />
          {busy && (
            <p class={styles.source}>
              Another long read of the audit trail is running. Trying again in a moment.
            </p>
          )}
        </div>
      )}
      {data && <Sealed batch={data} />}
    </div>
  );
}

export function Batch() {
  const number = route.value.param ?? '';
  return <BatchPage key={number} number={number} />;
}
