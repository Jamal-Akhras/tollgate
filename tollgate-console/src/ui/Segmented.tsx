import { useRef } from 'preact/hooks';
import styles from './Segmented.module.css';

type Option = { value: string; label: string };

type SegmentedProps = {
  label: string;
  options: Option[];
  value: string;
  onChange: (value: string) => void;
};

export function Segmented({ label, options, value, onChange }: SegmentedProps) {
  const list = useRef<HTMLDivElement>(null);

  function move(from: number, step: number) {
    const next = options[(from + step + options.length) % options.length];
    if (!next) {
      return;
    }
    onChange(next.value);
    const tabs = list.current?.querySelectorAll<HTMLElement>('[role="tab"]');
    tabs?.[options.indexOf(next)]?.focus();
  }

  function onKeyDown(event: KeyboardEvent, index: number) {
    if (event.key === 'ArrowRight') {
      event.preventDefault();
      move(index, 1);
    } else if (event.key === 'ArrowLeft') {
      event.preventDefault();
      move(index, -1);
    }
  }

  return (
    <div class={styles.list} role="tablist" aria-label={label} ref={list}>
      {options.map((option, index) => {
        const selected = option.value === value;
        return (
          <button
            key={option.value}
            type="button"
            role="tab"
            aria-selected={selected}
            tabIndex={selected ? 0 : -1}
            class={`${styles.tab} ${selected ? styles.selected : ''}`}
            onClick={() => onChange(option.value)}
            onKeyDown={(event) => onKeyDown(event, index)}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
