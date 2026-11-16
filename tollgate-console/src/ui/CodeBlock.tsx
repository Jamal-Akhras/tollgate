import { CopyButton } from './CopyButton';
import styles from './CodeBlock.module.css';

export function CodeBlock({ code }: { code: string }) {
  return (
    <div class={styles.block}>
      <pre class={styles.pre}>
        <code>{code}</code>
      </pre>
      <div class={styles.copy}>
        <CopyButton text={code} />
      </div>
    </div>
  );
}
