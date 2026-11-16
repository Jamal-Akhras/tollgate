import { useEffect } from 'preact/hooks';
import type { ConfigView } from '../../api/types';
import { GuardSwitch } from '../../edits/GuardSwitch';
import type { Rule } from '../../flow/rule';
import { href } from '../../router';
import { formatCount } from '../../ui/format';
import { Meter } from '../../ui/Meter';
import { moneyText } from '../../ui/Money';
import { setBudget, type Snippet } from '../Config/snippets';
import styles from './RuleCard.module.css';

type RuleCardProps = {
  rule: Rule;
  config: ConfigView;
  tenant: string;
  guard: string | null;
  onChange: (snippet: Snippet) => void;
  onChanged: () => void;
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

export function RuleCard({ rule, config, tenant, guard, onChange, onChanged, onClose }: RuleCardProps) {
  useEffect(() => {
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !document.querySelector('[role="dialog"]')) {
        onClose();
      }
    };
    document.addEventListener('keydown', escape);
    return () => document.removeEventListener('keydown', escape);
  }, [onClose]);

  const { counts, budget } = rule;
  const target = rule.guard;
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
      {target ? (
        <div class={styles.mode}>
          <GuardSwitch
            config={config}
            policy={target.policy}
            index={target.index}
            onChanged={onChanged}
          />
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
