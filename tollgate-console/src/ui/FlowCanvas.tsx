import type { ComponentChildren } from 'preact';
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import type { Flow, GuardCount } from '../api/types';
import type { Check, ProviderNode } from '../flow/describe';
import { layout, type Rect } from '../flow/layout';
import { linesFor } from '../flow/paths';
import styles from './FlowCanvas.module.css';
import { Cloud, Key, Server, Shield } from './icons';
import { NodeCard } from './NodeCard';
import { Pill } from './Pill';

export type TenantNode = { name: string; caption: string; selected: boolean };

type FlowCanvasProps = {
  tenants: TenantNode[];
  policy: string;
  checks: Check[];
  providers: ProviderNode[];
  flows: Flow[];
  guards: GuardCount[];
  now: number;
  preview: boolean;
  openCheck: string | null;
  onCheck: (id: string) => void;
  onSelect: (tenant: string) => void;
  legend: ComponentChildren;
  fallback: ComponentChildren;
};

function place(rect: Rect) {
  return { left: `${rect.x}px`, top: `${rect.y}px`, width: `${rect.w}px`, height: `${rect.h}px` };
}

function useWidth() {
  const element = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const target = element.current;
    if (!target) {
      return;
    }
    setWidth(target.clientWidth);
    const observer = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (entry) {
        setWidth(entry.contentRect.width);
      }
    });
    observer.observe(target);
    return () => observer.disconnect();
  }, []);
  return { element, width };
}

export function FlowCanvas({
  tenants,
  policy,
  checks,
  providers,
  flows,
  guards,
  now,
  preview,
  openCheck,
  onCheck,
  onSelect,
  legend,
  fallback,
}: FlowCanvasProps) {
  const { element, width } = useWidth();
  const [filter, setFilter] = useState('');
  const selected = tenants.find((tenant) => tenant.selected);
  const ordered = useMemo(
    () => (selected ? [selected, ...tenants.filter((tenant) => !tenant.selected)] : tenants),
    [tenants, selected],
  );
  const needle = filter.trim().toLowerCase();
  const visible = ordered.filter(
    (tenant) => tenant.selected || tenant.name.toLowerCase().includes(needle),
  );
  const drawing = layout(width, {
    tenants: tenants.length,
    checks: checks.length,
    providers: providers.length,
  });
  const first = drawing?.tenants[0];
  const last = drawing?.tenants[drawing.tenants.length - 1];
  const column =
    first && last
      ? { x: first.x - 3, y: first.y - 3, w: first.w + 11, h: last.y + last.h - first.y + 6 }
      : { x: 0, y: 0, w: 0, h: 0 };
  const lines = drawing
    ? linesFor(
        drawing,
        { flows, providers: providers.map((provider) => provider.name), checks, guards, preview },
        now,
      )
    : [];

  function onKeyDown(event: KeyboardEvent, index: number) {
    const step =
      event.key === 'ArrowDown' || event.key === 'ArrowRight'
        ? 1
        : event.key === 'ArrowUp' || event.key === 'ArrowLeft'
          ? -1
          : 0;
    const next = visible[(index + step + visible.length) % visible.length];
    if (step === 0 || !next) {
      return;
    }
    event.preventDefault();
    onSelect(next.name);
    element.current
      ?.querySelectorAll<HTMLElement>('[data-tenant]')
      .forEach((button) => button.dataset.tenant === next.name && button.focus());
  }

  return (
    <div class={`${styles.root} ${preview ? styles.previewing : ''}`} ref={element}>
      {!drawing && fallback}
      {drawing && (
        <div class={styles.picture} style={{ height: `${drawing.height}px` }}>
          <svg class={styles.lines} width={drawing.width} height={drawing.height} aria-hidden="true">
            {lines.map((line) => (
              <g key={line.id}>
                <path
                  d={line.d}
                  data-kind={line.kind}
                  data-moving={line.moving}
                  class={`${styles.line} ${styles[line.kind]} ${line.moving ? styles.moving : ''}`}
                />
                {line.moving && line.kind !== 'refused' && (
                  <circle class={styles.liveDot} cx={line.from.x} cy={line.from.y} r="3" />
                )}
                {line.end && (
                  <g transform={`translate(${line.end.x} ${line.end.y})`}>
                    <path class={styles.cross} d="M -5 -5 L 5 5 M 5 -5 L -5 5" />
                    <text class={styles.word} x="0" y="-12">
                      refused
                    </text>
                  </g>
                )}
              </g>
            ))}
          </svg>
          {first && (
            <div class={styles.column} style={place(column)}>
              {tenants.filter((tenant) => visible.includes(tenant)).map((tenant) => (
                <button
                  key={tenant.name}
                  type="button"
                  data-tenant={tenant.name}
                  class={styles.tenant}
                  style={{ order: visible.indexOf(tenant) }}
                  aria-pressed={tenant.selected}
                  tabIndex={tenant.selected ? 0 : -1}
                  onClick={() => onSelect(tenant.name)}
                  onKeyDown={(event) => onKeyDown(event, visible.indexOf(tenant))}
                >
                  <NodeCard
                    icon={<Key />}
                    title={tenant.name}
                    caption={tenant.caption}
                    stub={tenant.selected ? 'right' : undefined}
                    off={!tenant.selected}
                  />
                </button>
              ))}
            </div>
          )}
          {drawing.filter && (
            <input
              type="search"
              class={styles.filter}
              style={place(drawing.filter)}
              aria-label="Filter tenants"
              placeholder="Filter tenants"
              value={filter}
              onInput={(event) => setFilter(event.currentTarget.value)}
            />
          )}
          <section class={styles.box} style={place(drawing.policy)} aria-label={`Policy ${policy}`}>
            <h2 class={styles.boxTitle}>
              <Shield size={16} />
              <span>policy: {policy}</span>
            </h2>
          </section>
          {checks.map((check, index) => {
            const rect = drawing.pills[index];
            return (
              rect && (
                <div key={check.id} class={styles.pill} style={place(rect)}>
                  <Pill
                    label={check.label}
                    detail={check.detail}
                    state={check.state}
                    selected={openCheck === check.id}
                    onClick={() => onCheck(check.id)}
                  />
                </div>
              )
            );
          })}
          {providers.map((provider, index) => {
            const rect = drawing.providers[index];
            return (
              rect && (
                <div key={provider.name} class={styles.provider} style={place(rect)}>
                  <NodeCard
                    icon={provider.region === 'local' ? <Server /> : <Cloud />}
                    title={provider.name}
                    caption={provider.caption}
                    stub="left"
                    off={!provider.reachable}
                    dashed={provider.fallbackOnly}
                  />
                </div>
              )
            );
          })}
        </div>
      )}
      {drawing && <div class={styles.legend}>{legend}</div>}
    </div>
  );
}
