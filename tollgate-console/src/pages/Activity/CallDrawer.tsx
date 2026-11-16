import type { CallView } from '../../api/types';
import { href } from '../../router';
import { Badge } from '../../ui/Badge';
import { formatCount, formatMillis } from '../../ui/format';
import { Hash } from '../../ui/Hash';
import { Money } from '../../ui/Money';
import { Time } from '../../ui/Time';
import { Drawer } from '../../ui/Drawer';
import styles from './CallDrawer.module.css';
import { endpointName } from './filters';

type CallDrawerProps = { call: CallView; now: number; onClose: () => void };

function verdict(step: CallView['steps'][number]): string {
  if (step.allowed) {
    return 'allowed';
  }
  return step.monitored ? 'would have refused' : 'refused';
}

function Trace({ call }: { call: CallView }) {
  if (call.steps.length === 0) {
    return <p class={styles.note}>No guards ran for this call.</p>;
  }
  return (
    <ol class={styles.steps} aria-label="Decision trace">
      {call.steps.map((step) => (
        <li key={step.guard} class={styles.step}>
          <div class={styles.stepHead}>
            <span class={styles.guard}>{step.guard}</span>
            <Badge kind={step.monitored ? 'monitor' : 'enforce'} />
            <span class={step.allowed ? styles.allowed : styles.refusedText}>{verdict(step)}</span>
            <span class={styles.micros}>{step.micros} µs</span>
          </div>
          {step.message && <p class={styles.note}>{step.message}</p>}
        </li>
      ))}
    </ol>
  );
}

function Outcome({ call }: { call: CallView }) {
  const outcome = call.outcome;
  if (!call.allowed) {
    return (
      <p class={styles.note}>
        Refused with {call.code}
        {call.message ? `: ${call.message}` : ''}. Nothing was sent to the provider.
      </p>
    );
  }
  if (!outcome) {
    return <p class={styles.note}>No outcome recorded yet.</p>;
  }
  return (
    <dl class={styles.list}>
      <dt>Result</dt>
      <dd>{outcome.result}</dd>
      <dt>Status</dt>
      <dd>{outcome.status ?? 'none'}</dd>
      <dt>Tokens</dt>
      <dd>
        {outcome.tokens
          ? `${formatCount(outcome.tokens.input)} in, ${formatCount(outcome.tokens.output)} out`
          : 'none'}
      </dd>
      <dt>Cost</dt>
      <dd>
        <Money amount={outcome.cost.amount} currency={outcome.cost.currency} />
      </dd>
      <dt>Duration</dt>
      <dd>{formatMillis(outcome.millis)}</dd>
    </dl>
  );
}

export function CallDrawer({ call, now, onClose }: CallDrawerProps) {
  const redactions = Object.entries(call.redacted);
  return (
    <Drawer title={`${call.tenant} · ${call.model}`} onClose={onClose}>
      <div class={styles.body}>
        <section class={styles.section} aria-label="Call">
          <dl class={styles.list}>
            <dt>ID</dt>
            <dd>
              <Hash value={call.id} />
            </dd>
            <dt>Time</dt>
            <dd>
              <Time iso={call.time} now={now} />
            </dd>
            <dt>Key</dt>
            <dd class={styles.mono}>{call.key}</dd>
            <dt>Format</dt>
            <dd>{endpointName(call.endpoint)}</dd>
            <dt>Sent to</dt>
            <dd>
              {call.provider} as {call.upstream}
              {call.fallback && (
                <>
                  {' '}
                  <Badge kind="fallback" />
                </>
              )}
            </dd>
            <dt>Data class</dt>
            <dd>{call.dataClass ?? 'none'}</dd>
            <dt>User</dt>
            <dd>{call.attributed ? 'Attributed to a user' : 'Not attributed to a user'}</dd>
          </dl>
        </section>
        <section class={styles.section} aria-labelledby="trace-title">
          <h3 class={styles.heading} id="trace-title">
            Decision trace
          </h3>
          <Trace call={call} />
        </section>
        <section class={styles.section} aria-labelledby="reservation-title">
          <h3 class={styles.heading} id="reservation-title">
            Reservation
          </h3>
          {call.reservation ? (
            <Money amount={call.reservation.amount} currency={call.reservation.currency} />
          ) : (
            <p class={styles.note}>None.</p>
          )}
        </section>
        <section class={styles.section} aria-labelledby="redactions-title">
          <h3 class={styles.heading} id="redactions-title">
            Redactions
          </h3>
          {redactions.length === 0 ? (
            <p class={styles.note}>None.</p>
          ) : (
            <ul class={styles.plain}>
              {redactions.map(([detector, count]) => (
                <li key={detector}>
                  {detector}: {count}
                </li>
              ))}
            </ul>
          )}
        </section>
        <section class={styles.section} aria-labelledby="outcome-title">
          <h3 class={styles.heading} id="outcome-title">
            Outcome
          </h3>
          <Outcome call={call} />
        </section>
        <a class={styles.prove} href={href(`audit/records/${encodeURIComponent(call.id)}`)}>
          Prove this record
        </a>
      </div>
    </Drawer>
  );
}
