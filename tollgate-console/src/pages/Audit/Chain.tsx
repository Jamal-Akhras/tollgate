import { useState } from 'preact/hooks';
import { get } from '../../api/client';
import type { BatchDetail, BatchView, LeafView, RecordProofs } from '../../api/types';
import { href } from '../../router';
import { EmptyState } from '../../ui/EmptyState';
import { Hash } from '../../ui/Hash';
import { Check, ChevronDown, Cross } from '../../ui/icons';
import { RetryBanner } from '../../ui/RetryBanner';
import { Time } from '../../ui/Time';
import { isAuditOff } from '../auditOff';
import { Sealed } from './Batch';
import styles from './Chain.module.css';
import { ProofSteps } from './ProofSteps';
import { useLoaded } from './useLoaded';
import { Waiting } from './Waiting';

type Link = 'linked' | 'broken' | 'missing' | 'first' | 'unknown';

function linkOf(view: BatchView, older: BatchView | undefined): Link {
  if (view.seal.batch === 1) {
    return 'first';
  }
  if (!older) {
    return 'unknown';
  }
  if (older.seal.batch !== view.seal.batch - 1) {
    return 'missing';
  }
  return view.seal.previous === older.seal.root ? 'linked' : 'broken';
}

function Status({ link, batch }: { link: Link; batch: number }) {
  if (link === 'linked') {
    return (
      <span class={styles.linked} title={`Names the root of Batch ${batch - 1}`}>
        <Check size={14} /> Linked
      </span>
    );
  }
  if (link === 'broken' || link === 'missing') {
    const why =
      link === 'missing'
        ? `Batch ${batch - 1} is missing`
        : `Does not name the root of Batch ${batch - 1}`;
    return (
      <span class={styles.broken} title={why}>
        <Cross size={14} /> Broken link
      </span>
    );
  }
  return (
    <span class={styles.plain}>{link === 'first' ? 'First batch' : 'Older not shown'}</span>
  );
}

function LeafProof({ batch, leaf }: { batch: number; leaf: LeafView }) {
  const { data, error, busy, retry } = useLoaded(
    () => get<RecordProofs>(`/audit/records/${encodeURIComponent(leaf.id)}`),
    `${batch}/${leaf.index}`,
  );
  if (error) {
    return <RetryBanner error={error} onRetry={retry} />;
  }
  if (!data) {
    return <Waiting height="120px" busy={busy} />;
  }
  const proved = [data.intent, data.outcome].find(
    (item) => item?.seal?.batch === batch && item.proof?.index === leaf.index,
  );
  return (
    <section class={styles.proof} aria-label={`Proof for leaf ${leaf.index}`}>
      <h3 class={styles.heading}>
        Leaf {leaf.index}, the {leaf.type} of{' '}
        <a href={href(`audit/records/${leaf.id}`)}>this request</a>
      </h3>
      {proved?.proof ? (
        <ProofSteps proof={proved.proof} seal={proved.seal} signatureValid={proved.signatureValid} />
      ) : (
        <p class={styles.muted}>The server returned no proof for this leaf.</p>
      )}
    </section>
  );
}

function Details({ view, older }: { view: BatchView; older: BatchView | undefined }) {
  const batch = view.seal.batch;
  const { data, error, busy, retry } = useLoaded(
    () => get<BatchDetail>(`/audit/batches/${batch}`),
    batch,
  );
  const [leaf, setLeaf] = useState<LeafView | null>(null);
  return (
    <div class={styles.details} id={`batch-${batch}`}>
      <p class={styles.equation}>
        <span class={styles.muted}>Signed over</span>
        <span class={styles.term}>
          previous root <Hash value={view.seal.previous} />
        </span>
        <span class={styles.muted}>+</span>
        <span class={styles.term}>
          this root <Hash value={view.seal.root} />
        </span>
        {older && older.seal.batch === batch - 1 && (
          <span class={styles.term}>
            <span class={styles.muted}>Batch {older.seal.batch}'s root is</span>
            <Hash value={older.seal.root} />
          </span>
        )}
      </p>
      {error && <RetryBanner error={error} onRetry={retry} />}
      {!data && !error && <Waiting height="160px" busy={busy} />}
      {data && (
        <>
          <Sealed batch={data} selected={leaf?.index} onChoose={setLeaf} />
          {leaf ? (
            <LeafProof batch={batch} leaf={leaf} />
          ) : (
            <p class={styles.muted}>Choose a leaf in the tree to walk its proof up to the root.</p>
          )}
        </>
      )}
    </div>
  );
}

export function Chain({ lastBatch }: { lastBatch: number | null }) {
  const { data, error, busy, retry } = useLoaded(
    () => get<BatchView[]>('/audit/batches', { limit: 50 }),
    lastBatch ?? 0,
    true,
  );
  const [open, setOpen] = useState<number | null>(null);

  if (error && !isAuditOff(error)) {
    return <RetryBanner error={error} onRetry={retry} />;
  }
  if (!data) {
    return <Waiting height="110px" busy={busy} />;
  }
  if (data.length === 0) {
    return (
      <EmptyState
        title="No sealed batches yet"
        text="Tollgate seals the audit trail in batches. The first one appears after the first call."
      />
    );
  }
  const now = Date.now();
  const ordered = [...data].sort((a, b) => b.seal.batch - a.seal.batch);
  return (
    <section aria-labelledby="chain-title">
      <h2 id="chain-title" class={styles.title}>
        The chain
      </h2>
      <p class={styles.note}>
        The latest {ordered.length} sealed batches, newest first. Each batch names the root of the
        one before it, and its signature covers both. This list checks the names; signatures are
        checked when you open a batch or verify the whole trail.
      </p>
      <div class={styles.table}>
        <div class={`${styles.row} ${styles.head}`}>
          <span />
          <span>Batch</span>
          <span>Records</span>
          <span>Sealed</span>
          <span>Merkle root</span>
          <span>Status</span>
          <span />
        </div>
        <ol class={styles.list}>
          {ordered.map((view, index) => {
            const batch = view.seal.batch;
            const older = ordered[index + 1];
            const link = linkOf(view, older);
            const expanded = open === batch;
            const cut = link === 'broken' || link === 'missing';
            return (
              <li key={batch} class={cut ? styles.bad : undefined}>
                <div class={styles.row}>
                  <span class={styles.rail} aria-hidden="true">
                    <span class={styles.dot} />
                  </span>
                  <a class={styles.number} href={href(`audit/batches/${batch}`)}>
                    Batch {batch}
                  </a>
                  <span class={styles.muted}>
                    {view.seal.records} {view.seal.records === 1 ? 'record' : 'records'}
                  </span>
                  <Time iso={view.seal.time} now={now} />
                  <Hash value={view.seal.root} />
                  <Status link={link} batch={batch} />
                  <button
                    type="button"
                    class={styles.toggle}
                    aria-expanded={expanded}
                    aria-controls={`batch-${batch}`}
                    aria-label={`Details of Batch ${batch}`}
                    onClick={() => setOpen(expanded ? null : batch)}
                  >
                    <ChevronDown size={16} />
                  </button>
                </div>
                {expanded && <Details view={view} older={older} />}
              </li>
            );
          })}
        </ol>
      </div>
    </section>
  );
}
