import { Cross } from './icons';
import styles from './Pill.module.css';

export type PillState = 'ok' | 'refused' | 'monitor' | 'off';

type PillProps = {
  label: string;
  detail: string;
  state: PillState;
  selected?: boolean;
  onClick?: () => void;
};

export function Pill({ label, detail, state, selected = false, onClick }: PillProps) {
  return (
    <button
      type="button"
      class={`${styles.pill} ${selected ? styles.selected : ''}`}
      aria-pressed={selected}
      onClick={onClick}
    >
      {state === 'refused' ? (
        <span class={`${styles.mark} ${styles.refused}`}>
          <Cross size={12} />
        </span>
      ) : (
        <span class={`${styles.dot} ${styles[state]}`} data-state={state} />
      )}
      <span class={styles.label}>{label}</span>
      <span class={styles.detail}>{detail}</span>
    </button>
  );
}
