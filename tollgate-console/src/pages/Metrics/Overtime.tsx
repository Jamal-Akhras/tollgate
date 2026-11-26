import { useMemo, useState } from 'preact/hooks';
import type { LiveSnapshot } from '../../api/types';
import { formatDecimal } from '../../ui/format';
import { PlotChart } from '../../ui/PlotChart';
import { Segmented } from '../../ui/Segmented';
import { chartFor, type Metric } from './series';

const options: { value: Metric; label: string }[] = [
  { value: 'calls', label: 'Calls' },
  { value: 'spend', label: 'Spend' },
  { value: 'tokens', label: 'Tokens' },
  { value: 'latency', label: 'Latency' },
];

function spendFormat(value: number): string {
  return String(Math.round(value * 1_000_000) / 1_000_000);
}

type OvertimeProps = { live: LiveSnapshot; minutes: number };

export function Overtime({ live, minutes }: OvertimeProps) {
  const [metric, setMetric] = useState<Metric>('calls');
  const chart = useMemo(() => chartFor(live, metric), [live, metric]);
  const name = options.find((option) => option.value === metric)?.label ?? '';
  return (
    <PlotChart
      title="Over time"
      dataLabel={name}
      unit={chart.unit}
      window={`last ${minutes} minutes`}
      x={chart.x}
      lines={chart.lines}
      format={metric === 'spend' ? spendFormat : formatDecimal}
      stacked={chart.stacked}
      bars={chart.bars}
      empty="No calls in this window."
      actions={<Segmented label="Chart" options={options} value={metric} onChange={setMetric} />}
    />
  );
}
