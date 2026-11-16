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
  onSelect: (tenant: string) => void;
  fallback: ComponentChildren;
};

function place(rect: Rect) {
  return { left: rect.x, top: rect.y, width: rect.w, height: rect.h };
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
  onSelect,
  fallback,
}: FlowCanvasProps) {
  const { element, width } = useWidth();
  const selected = tenants.find((tenant) => tenant.selected);
  const ordered = useMemo(
    () => (selected ? [selected, ...tenants.filter((tenant) => !tenant.selected)] : tenants),
    [tenants, selected],
  );
  const drawing = layout(width, {
    tenants: tenants.length,
    checks: checks.length,
    providers: providers.length,
  });
  const lines = drawing
    ? linesFor(
        drawing,
        { flows, providers: providers.map((provider) => provider.name), checks, guards },
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
    const next = tenants[(index + step + tenants.length) % tenants.length];
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
    <div class={styles.root} ref={element}>
      {!drawing && fallback}
      {drawing && (
        <div class={styles.picture} style={{ height: drawing.height }}>
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
          {tenants.map((tenant, index) => {
            const rect = drawing.tenants[ordered.indexOf(tenant)];
            return (
              rect && (
                <button
                  key={tenant.name}
                  type="button"
                  data-tenant={tenant.name}
                  class={styles.tenant}
                  style={place(rect)}
                  aria-pressed={tenant.selected}
                  tabIndex={tenant.selected ? 0 : -1}
                  onClick={() => onSelect(tenant.name)}
                  onKeyDown={(event) => onKeyDown(event, index)}
                >
                  <NodeCard
                    icon={<Key />}
                    title={tenant.name}
                    caption={tenant.caption}
                    stub={tenant.selected ? 'right' : undefined}
                    off={!tenant.selected}
                  />
                </button>
              )
            );
          })}
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
                  <Pill label={check.label} detail={check.detail} state={check.state} />
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
    </div>
  );
}
