import { useEffect } from 'preact/hooks';
import type { Rule } from '../../flow/rule';
import { href } from '../../router';
import { formatCount } from '../../ui/format';
import { Meter } from '../../ui/Meter';
import { moneyText } from '../../ui/Money';
import { Switch } from '../../ui/Switch';
import { guardMode, setBudget, type Snippet } from '../Config/snippets';
import styles from './RuleCard.module.css';

type RuleCardProps = {
  rule: Rule;
  tenant: string;
  guard: string | null;
  onChange: (snippet: Snippet) => void;
  onClose: () => void;
};

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div class={styles.row}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

export function RuleCard({ rule, tenant, guard, onChange, onClose }: RuleCardProps) {
  useEffect(() => {
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !document.querySelector('[role="dialog"]')) {
        onClose();
      }
    };
    document.addEventListener('keydown', escape);
    return () => document.removeEventListener('keydown', escape);
  }, [onClose]);

  const { counts, budget, mode } = rule;
  const other = mode === 'enforce' ? 'monitor' : 'enforce';
  const target = rule.guard;
  const how = target
    ? () => onChange(guardMode(target.policy, target.index, target.type, other))
    : null;
  const currency = budget?.tenant.budget?.currency ?? '';
  const money = (n: number) => moneyText({ amount: String(n), currency });
  const query: Record<string, string> = guard ? { tenant, guard } : { tenant };

  return (
    <section class={styles.card} aria-label={`Rule ${rule.title}`}>
      <div class={styles.head}>
        <h2 class={styles.title}>{rule.title}</h2>
        <span class={styles.calls}>
          {formatCount(rule.calls)} {rule.calls === 1 ? 'call' : 'calls'} in the last 15 minutes
        </span>
      </div>
      <p class={styles.what}>{rule.what}</p>
      <dl class={styles.rows}>
        {counts && (
          <>
            <Row label="Allowed" value={formatCount(counts.allowed)} />
            <Row label="Refused" value={formatCount(counts.refused)} />
            <Row label="Would have refused (monitor)" value={formatCount(counts.monitored)} />
          </>
        )}
        {rule.facts.map((fact) => (
          <Row key={fact.label} label={fact.label} value={fact.value} />
        ))}
      </dl>
      {!rule.perCheck && <p class={styles.note}>This check has no totals of its own.</p>}
      {budget?.state && (
        <Meter
          label="Spent"
          value={Number(budget.state.spent)}
          limit={Number(budget.state.limit)}
          format={money}
        />
      )}
      {target && how ? (
        <div class={styles.mode}>
          <Switch
            label={`Enforce ${target.type}`}
            checked={mode === 'enforce'}
            onWord="Enforcing"
            offWord="Monitoring"
            onChange={how}
          />
          <button type="button" class={styles.button} onClick={how}>
            How to switch this to {other}
          </button>
        </div>
      ) : (
        <p class={styles.always}>Always enforces</p>
      )}
      {budget && (
        <div>
          <button
            type="button"
            class={styles.button}
            onClick={() => onChange(setBudget(tenant, budget.tenant.budget))}
          >
            How to change this budget
          </button>
        </div>
      )}
      <a href={href('activity', query)}>See these calls in Activity</a>
    </section>
  );
}
