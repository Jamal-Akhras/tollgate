import type { ComponentChildren } from 'preact';
import styles from './NodeCard.module.css';

type NodeCardProps = {
  icon: ComponentChildren;
  title: string;
  caption: string;
  stub?: 'left' | 'right';
  off?: boolean;
  dashed?: boolean;
};

export function NodeCard({ icon, title, caption, stub, off, dashed }: NodeCardProps) {
  const classes = [styles.node, off ? styles.off : '', dashed ? styles.dashed : ''].join(' ');
  return (
    <div class={classes}>
      {stub && <span data-testid="stub" class={`${styles.stub} ${styles[stub]}`} />}
      <span class={styles.tile}>{icon}</span>
      <div class={styles.text}>
        <div class={styles.title}>{title}</div>
        <div class={styles.caption}>{caption}</div>
      </div>
    </div>
  );
}
