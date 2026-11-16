import styles from './Switch.module.css';

type SwitchProps = {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  onWord?: string;
  offWord?: string;
};

export function Switch({ label, checked, onChange, onWord = 'On', offWord = 'Off' }: SwitchProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      class={`${styles.switch} ${checked ? styles.on : ''}`}
      onClick={() => onChange(!checked)}
    >
      <span class={styles.track}>
        <span class={styles.knob} />
      </span>
      <span class={styles.word} aria-hidden="true">
        {checked ? onWord : offWord}
      </span>
    </button>
  );
}
