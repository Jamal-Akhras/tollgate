import { useEffect, useState } from 'preact/hooks';
import type { ProofView, SealView } from '../../api/types';
import { canHash, leafHash, verifyProof, type Verified } from '../../merkle/proof';
import { Hash } from '../../ui/Hash';
import { Check, Cross } from '../../ui/icons';
import styles from './ProofSteps.module.css';

export const cannotHashText =
  "Your browser can't hash here (it needs HTTPS or localhost). This is the server's result.";

type Checked = { leaf: string; verified: Verified } | 'unreadable';

type ProofStepsProps = { proof: ProofView; seal: SealView | null; signatureValid: boolean | null };

function Mark({ good }: { good: boolean }) {
  return good ? (
    <span class={styles.good}>
      <Check size={14} />
    </span>
  ) : (
    <span class={styles.bad}>
      <Cross size={14} />
    </span>
  );
}

function Comparison({ left, right }: { left: string; right: string }) {
  const equal = left === right;
  return (
    <span class={styles.compare}>
      <Mark good={equal} />
      <span class={equal ? styles.good : styles.bad}>{equal ? 'equal' : 'not equal'}</span>
    </span>
  );
}

function Pending() {
  return <span class={styles.pending}>…</span>;
}

function Signature({ seal, valid }: { seal: SealView | null; valid: boolean | null }) {
  if (!seal) {
    return null;
  }
  return (
    <li class={styles.step}>
      <span class={styles.label}>
        The batch's seal is signed by key <Hash value={seal.key} />
      </span>
      <span class={styles.result}>
        <Mark good={valid === true} />
        <span class={valid === true ? styles.good : styles.bad}>
          {valid === true ? 'valid' : 'not valid'}
        </span>
        <span class={styles.source}>as the server reports it</span>
      </span>
    </li>
  );
}

export function ProofSteps({ proof, seal, signatureValid }: ProofStepsProps) {
  const hashes = canHash();
  const [checked, setChecked] = useState<Checked | null>(null);

  useEffect(() => {
    setChecked(null);
    if (!hashes) {
      return undefined;
    }
    let stopped = false;
    (async () => {
      try {
        const leaf = await leafHash(proof.encoding);
        const verified = await verifyProof({ ...proof, leafHash: leaf });
        if (!stopped) {
          setChecked({ leaf, verified });
        }
      } catch {
        if (!stopped) {
          setChecked('unreadable');
        }
      }
    })();
    return () => {
      stopped = true;
    };
  }, [proof, hashes]);

  if (!hashes) {
    return (
      <div class={styles.steps}>
        <ol class={styles.list}>
          <li class={styles.step}>
            <span class={styles.label}>The record's leaf hash</span>
            <Hash value={proof.leafHash} />
          </li>
          <li class={styles.step}>
            <span class={styles.label}>
              Combined with {proof.path.length} {proof.path.length === 1 ? 'sibling' : 'siblings'}{' '}
              up to the root
            </span>
            <Hash value={proof.root} />
          </li>
          {seal && (
            <li class={styles.step}>
              <span class={styles.label}>The proof's root compared with the seal's root</span>
              <Comparison left={proof.root} right={seal.root} />
            </li>
          )}
          <Signature seal={seal} valid={signatureValid} />
        </ol>
        <p class={styles.source}>{cannotHashText}</p>
      </div>
    );
  }

  if (checked === 'unreadable') {
    return (
      <p class={styles.failure}>The proof's encoding could not be read, so it cannot be checked.</p>
    );
  }

  const done = checked !== null;
  const leaf = done ? checked.leaf : '';
  const verified = done ? checked.verified : null;
  const reached = verified?.root ?? '';
  const sealRoot = seal?.root ?? proof.root;
  const holds =
    done &&
    leaf === proof.leafHash &&
    verified !== null &&
    verified.matches &&
    proof.root === sealRoot;

  return (
    <div class={styles.steps}>
      <ol class={styles.list}>
        <li class={styles.step}>
          <span class={styles.label}>Hash the record's encoding</span>
          {done ? (
            <span class={styles.result}>
              <Hash value={leaf} />
              <span class={styles.source}>against the proof's</span>
              <Comparison left={leaf} right={proof.leafHash} />
            </span>
          ) : (
            <Pending />
          )}
        </li>
        {proof.path.map((sibling, index) => {
          const step = verified?.steps[index];
          return (
            <li class={styles.step} key={`${index}-${sibling}`}>
              <span class={styles.label}>
                Combine with <Hash value={sibling} /> on the {step ? step.side : '…'}
              </span>
              {step ? <Hash value={step.result} /> : <Pending />}
            </li>
          );
        })}
        <li class={styles.step}>
          <span class={styles.label}>Compare with the batch's root</span>
          {done ? (
            <span class={styles.result}>
              <Hash value={reached} />
              <span class={styles.source}>against</span>
              <Hash value={proof.root} />
              <Comparison left={reached} right={proof.root} />
            </span>
          ) : (
            <Pending />
          )}
        </li>
        {seal && (
          <li class={styles.step}>
            <span class={styles.label}>Compare the proof's root with the seal's root</span>
            <Comparison left={proof.root} right={seal.root} />
          </li>
        )}
        <Signature seal={seal} valid={signatureValid} />
      </ol>
      {done && (
        <p class={holds ? styles.verdict : styles.failure} role="status">
          {holds ? (
            <>
              <Mark good />
              <span>Checked in your browser</span>
            </>
          ) : (
            <>
              <Mark good={false} />
              <span>
                The proof does not hold. It reached root <Hash value={reached} /> and the batch's
                root is <Hash value={sealRoot} />.
              </span>
            </>
          )}
        </p>
      )}
    </div>
  );
}
