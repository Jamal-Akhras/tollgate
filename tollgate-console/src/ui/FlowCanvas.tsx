import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import type { Flow, GuardCount } from '../api/types';
import type { Check, ProviderNode } from '../flow/describe';
import { layout, type Rect } from '../flow/layout';
import { formatCount } from './format';
import { linesFor } from '../flow/paths';
import styles from './FlowCanvas.module.css';
import { Cloud, Cross, Key, Server, Shield } from './icons';
import { NodeCard } from './NodeCard';
import { Pill } from './Pill';

export type TenantNode = { name: string; caption: string; selected: boolean; linked: boolean };

type FlowCanvasProps = {
  tenants: TenantNode[];
  policy: string;
  checks: Check[];
  providers: ProviderNode[];
  refused: { calls: number; href: string };
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

const steps: Record<string, number | undefined> = {
  ArrowDown: 1,
  ArrowRight: 1,
  ArrowUp: -1,
  ArrowLeft: -1,
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
  refused,
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
  const needle = filter.trim().toLowerCase();
  const [scrolled, setScrolled] = useState(0);
  const visible = tenants.filter(
    (tenant) => tenant.selected || tenant.name.toLowerCase().includes(needle),
  );
  const selected = visible.find((tenant) => tenant.selected)?.name;
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
      : undefined;
  const step = first ? (drawing?.tenants[1]?.y ?? first.y + first.h) - first.y : 0;
  const origins =
    first && column
      ? visible
          .map((tenant, index) => ({
            name: tenant.name,
            selected: tenant.selected,
            linked: tenant.linked,
            rect: { ...first, y: first.y + index * step - scrolled },
          }))
          .filter(
            ({ rect, linked }) =>
              linked &&
              rect.y + rect.h / 2 >= column.y &&
              rect.y + rect.h / 2 <= column.y + column.h,
          )
      : [];
  const lines = drawing
    ? linesFor(
        drawing,
        {
          tenants: origins,
          selected: selected ?? '',
          flows,
          providers: providers.map((provider) => provider.name),
          checks,
          guards,
          preview,
        },
        now,
      )
    : [];
  const scrolls = Boolean(drawing?.filter);

  useEffect(() => {
    if (scrolls && selected) {
      element.current
        ?.querySelectorAll<HTMLElement>('[data-tenant]')
        .forEach(
          (button) =>
            button.dataset.tenant === selected && button.scrollIntoView?.({ block: 'nearest' }),
        );
    }
  }, [scrolls, selected]);

  function onKeyDown(event: KeyboardEvent, index: number) {
    const step = steps[event.key];
    if (step === undefined) {
      return;
    }
    const next = visible[(index + step + visible.length) % visible.length];
    if (!next) {
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
          <svg
            class={styles.lines}
            width={drawing.width}
            height={drawing.height}
            aria-hidden="true"
          >
            {lines.map((line) => (
              <g key={line.id}>
                <path
                  d={line.d}
                  data-kind={line.kind}
                  data-moving={line.moving}
                  class={[
                    styles.line,
                    styles[line.kind],
                    line.moving ? styles.moving : '',
                    line.fast ? styles.fast : '',
                    line.faint ? styles.faint : '',
                  ].join(' ')}
                />
                {line.fast && !line.faint && line.kind !== 'refused' && (
                  <circle class={styles.liveDot} cx={line.from.x} cy={line.from.y} r="3" />
                )}
              </g>
            ))}
          </svg>
          {column && (
            <div
              class={styles.column}
              style={place(column)}
              onScroll={(event) => setScrolled(event.currentTarget.scrollTop)}
            >
              {visible.map((tenant, index) => (
                <button
                  key={tenant.name}
                  type="button"
                  data-tenant={tenant.name}
                  class={styles.tenant}
                  aria-pressed={tenant.selected}
                  tabIndex={tenant.selected ? 0 : -1}
                  onClick={() => onSelect(tenant.name)}
                  onKeyDown={(event) => onKeyDown(event, index)}
                >
                  <NodeCard
                    icon={<Key />}
                    title={tenant.name}
                    caption={tenant.caption}
                    stub={tenant.linked ? 'right' : undefined}
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
          <a
            class={`${styles.refused} ${refused.calls > 0 ? styles.refusing : ''}`}
            style={place(drawing.refused)}
            href={refused.href}
            aria-label={`See the ${formatCount(refused.calls)} calls refused in the last 15 minutes`}
          >
            <NodeCard
              icon={<Cross />}
              title="Refused"
              caption={
                refused.calls === 0
                  ? 'None in the last 15 minutes'
                  : `${formatCount(refused.calls)} ${refused.calls === 1 ? 'call' : 'calls'} · last 15 minutes`
              }
              stub="left"
              off={refused.calls === 0 || preview}
            />
          </a>
        </div>
      )}
      {drawing && <div class={styles.legend}>{legend}</div>}
    </div>
  );
}
