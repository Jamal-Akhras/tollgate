import { useEffect, useRef, useState } from 'preact/hooks';
import { ApiError, get } from '../../api/client';
import type { ProblemView, Verification } from '../../api/types';
import { Banner } from '../../ui/Banner';
import { Card } from '../../ui/Card';
import styles from './Verify.module.css';

const busyRetryMillis = 2000;

export const warningsText =
  'A running server always has a few of these: the batch that is still open, and calls still in flight.';

type State =
  | { kind: 'idle' }
  | { kind: 'running' }
  | { kind: 'done'; result: Verification }
  | { kind: 'failed'; error: Error };

function Problems({ problems }: { problems: ProblemView[] }) {
  return (
    <ul class={styles.problems}>
      {problems.map((problem, index) => (
        <li key={`${problem.file}:${problem.line}:${index}`}>
          <span class={styles.where}>
            {problem.file}:{problem.line}
          </span>{' '}
          {problem.message}
        </li>
      ))}
    </ul>
  );
}

export function Verify() {
  const [state, setState] = useState<State>({ kind: 'idle' });
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => () => clearTimeout(timer.current), []);

  function run() {
    clearTimeout(timer.current);
    setState({ kind: 'running' });
    get<Verification>('/audit/verification')
      .then((result) => setState({ kind: 'done', result }))
      .catch((error: Error) => {
        if (error instanceof ApiError && error.status === 429) {
          timer.current = setTimeout(run, busyRetryMillis);
        } else {
          setState({ kind: 'failed', error });
        }
      });
  }

  const running = state.kind === 'running';
  const result = state.kind === 'done' ? state.result : null;

  return (
    <Card>
      <div class={styles.row}>
        <button type="button" class={styles.button} disabled={running} onClick={run}>
          Verify the whole trail
        </button>
        {running && <span class={styles.source}>Reading the trail…</span>}
      </div>
      <div role="status">
        {result && result.intact && (
          <p class={styles.good}>
            The audit trail is intact: {result.records} records in {result.batches} sealed batches
          </p>
        )}
        {result && !result.intact && <p class={styles.bad}>The audit trail is not intact</p>}
      </div>
      {result && result.failures.length > 0 && (
        <>
          <h2 class={styles.heading}>Failures</h2>
          <Problems problems={result.failures} />
        </>
      )}
      {result && result.warnings.length > 0 && (
        <>
          <h2 class={styles.heading}>Warnings</h2>
          <Problems problems={result.warnings} />
          <p class={styles.source}>{warningsText}</p>
        </>
      )}
      {state.kind === 'failed' && <Banner kind="error">{state.error.message}</Banner>}
    </Card>
  );
}
