import { CodeBlock } from './CodeBlock';
import styles from './EmptyState.module.css';

type EmptyStateProps = { title: string; text: string; code?: string };

export function EmptyState({ title, text, code }: EmptyStateProps) {
  return (
    <div class={styles.empty}>
      <h2 class={styles.title}>{title}</h2>
      <p class={styles.text}>{text}</p>
      {code && <CodeBlock code={code} />}
    </div>
  );
}
