import type { ComponentType } from 'preact';
import { href } from '../router';
import { signOut } from '../api/client';
import { BudgetCard } from './BudgetCard';
import { Activity, Budget, Chart, Document, Flow, Power, Settings, Shield } from './icons';
import styles from './SideNav.module.css';

type Item = { section: string; label: string; icon: ComponentType<{ size?: number }> };

const items: Item[] = [
  { section: 'flow', label: 'Flow', icon: Flow },
  { section: 'activity', label: 'Activity', icon: Activity },
  { section: 'metrics', label: 'Metrics', icon: Chart },
  { section: 'usage', label: 'Usage', icon: Budget },
  { section: 'audit', label: 'Audit', icon: Shield },
  { section: 'config', label: 'Config', icon: Settings },
];

const docs = 'https://github.com/Jamal-Akhras/tollgate#readme';

type SideNavProps = {
  current: string;
  collapsed: boolean;
  drawer: boolean;
  onNavigate: () => void;
};

export function SideNav({ current, collapsed, drawer, onNavigate }: SideNavProps) {
  const classes = [styles.nav, collapsed ? styles.collapsed : '', drawer ? styles.drawer : ''];
  return (
    <nav class={classes.join(' ')} aria-label="Main">
      <ul class={styles.list}>
        {items.map((item) => (
          <li key={item.section}>
            <a
              class={`${styles.link} ${current === item.section ? styles.current : ''}`}
              href={href(item.section)}
              aria-current={current === item.section ? 'page' : undefined}
              title={collapsed ? item.label : undefined}
              onClick={onNavigate}
            >
              <item.icon />
              <span class={styles.label}>{item.label}</span>
            </a>
          </li>
        ))}
      </ul>
      <hr class={styles.rule} />
      <a
        class={styles.link}
        href={docs}
        target="_blank"
        rel="noopener"
        title={collapsed ? 'Docs' : undefined}
      >
        <Document />
        <span class={styles.label}>Docs</span>
      </a>
      <div class={styles.spacer} />
      <div class={styles.budget}>
        <BudgetCard />
      </div>
      <button
        type="button"
        class={styles.link}
        title={collapsed ? 'Sign out' : undefined}
        onClick={signOut}
      >
        <Power />
        <span class={styles.label}>Sign out</span>
      </button>
    </nav>
  );
}
