import type { LiveSnapshot } from '../../api/types';
import { statsFor } from '../../flow/stats';
import { href } from '../../router';
import { formatCount, formatMillis } from '../../ui/format';
import { moneyText } from '../../ui/Money';
import { StatTile } from '../../ui/StatTile';
import styles from './StatTiles.module.css';

function spendText(spend: Record<string, string>): string {
  const parts = Object.entries(spend).map(([currency, amount]) => moneyText({ amount, currency, short: true }));
  return parts.length === 0 ? '0' : parts.join(' + ');
}

export function StatTiles({ live }: { live: LiveSnapshot }) {
  const stats = statsFor(live);
  const metrics = href('metrics');
  return (
    <div class={styles.tiles}>
      <a class={styles.link} href={metrics}>
        <StatTile
          caption="Calls per minute"
          value={formatCount(stats.callsPerMinute)}
          unit="calls/min"
          series={stats.callSeries}
          window="Last minute"
        />
      </a>
      <a class={styles.link} href={metrics}>
        <StatTile
          caption="Refused"
          value={formatCount(stats.refused)}
          unit="calls"
          series={stats.refusedSeries}
          window="Last 15 minutes"
        />
      </a>
      <a class={styles.link} href={metrics}>
        <StatTile
          caption="Spend"
          value={spendText(stats.spend)}
          series={stats.spendSeries}
          window="Last 15 minutes"
        />
      </a>
      <a class={styles.link} href={metrics}>
        <StatTile
          caption="Latency, p95"
          value={stats.p95Millis === null ? '–' : formatMillis(stats.p95Millis)}
          series={stats.latencySeries}
          window="Last 5 minutes"
        />
      </a>
    </div>
  );
}
