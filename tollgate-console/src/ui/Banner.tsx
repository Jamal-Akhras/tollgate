import type { ComponentChildren } from 'preact';
import styles from './Banner.module.css';

type BannerProps = {
  kind: 'info' | 'warn' | 'error';
  children: ComponentChildren;
  action?: ComponentChildren;
};

export function Banner({ kind, children, action }: BannerProps) {
  return (
    <div class={`${styles.banner} ${styles[kind]}`} role={kind === 'error' ? 'alert' : 'status'}>
      <div class={styles.text}>{children}</div>
      {action}
    </div>
  );
}
