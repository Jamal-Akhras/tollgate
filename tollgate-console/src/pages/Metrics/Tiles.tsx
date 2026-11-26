import type { LiveSnapshot } from '../../api/types';
import { formatCount, formatDecimal, formatMillis } from '../../ui/format';
import { MetricTile } from '../../ui/MetricTile';
import {
  changeText,
  directionText,
  enoughCalls,
  failedOf,
  failedShare,
  paceText,
  percentText,
  perMinute,
  plural,
  pointsText,
  refusedShare,
  spendChange,
  spendText,
  totalCalls,
  withCurrencies,
  type Change,
} from './figures';
import styles from './Metrics.module.css';
import { sparksFor } from './series';
import { before } from './windows';

type TilesProps = { live: LiveSnapshot; minutes: number };

function shown(change: Change | null, worseWhenUp = false) {
  return change && { text: change.text, bad: worseWhenUp && change.up };
}

export function Tiles({ live, minutes }: TilesProps) {
  const { totals, previous } = live;
  const since = before(minutes);
  const sparks = sparksFor(live);
  const calls = totalCalls(totals);
  const failed = failedOf(totals);
  const rate = perMinute(calls, totals.seconds);
  const priorRate = previous ? perMinute(totalCalls(previous), previous.seconds) : null;
  const latencyKnown =
    totals.sent >= enoughCalls && totals.p50Millis !== null && totals.p95Millis !== null;
  const priorLatency =
    previous && previous.sent >= enoughCalls ? previous.p95Millis : null;

  return (
    <div>
      <div class={styles.tiles}>
        <MetricTile
          caption="Calls"
          value={formatCount(calls)}
          detail={rate === null ? '–' : `${formatDecimal(rate)} per minute`}
          change={previous ? shown(changeText(rate, priorRate, since)) : null}
          series={sparks.calls}
        />
        <MetricTile
          caption="Failed"
          value={percentText(failedShare(totals))}
          detail={
            totals.serverErrors > 0
              ? `${plural(failed, 'call')} · ${plural(totals.serverErrors, 'provider error')}`
              : plural(failed, 'call')
          }
          change={
            previous
              ? shown(pointsText(failedShare(totals), failedShare(previous), since), true)
              : null
          }
          series={sparks.failed}
          danger
        />
        <MetricTile
          caption="Refused"
          value={percentText(refusedShare(totals))}
          detail={plural(totals.refused, 'call')}
          change={
            previous ? shown(pointsText(refusedShare(totals), refusedShare(previous), since)) : null
          }
          series={sparks.refused}
        />
        <MetricTile
          caption="Spend"
          value={spendText(withCurrencies(totals.cost, Object.keys(previous?.cost ?? {})))}
          detail={paceText(totals.cost, totals.seconds)}
          change={previous ? shown(spendChange(totals, previous, since)) : null}
          series={sparks.spend}
        />
        <MetricTile
          caption="Latency p95"
          value={latencyKnown && totals.p95Millis !== null ? formatMillis(totals.p95Millis) : '–'}
          detail={
            latencyKnown && totals.p50Millis !== null
              ? `p50 ${formatMillis(totals.p50Millis)} · ${plural(totals.sent, 'call')}`
              : 'too few calls'
          }
          change={
            latencyKnown && totals.p95Millis !== null && priorLatency !== null
              ? shown(directionText(totals.p95Millis, priorLatency, since))
              : null
          }
          series={sparks.latency}
        />
      </div>
      <p class={styles.note}>
        Calls are counted per attempt: a call rescued by a fallback counts as one failure and two
        calls.
      </p>
    </div>
  );
}
