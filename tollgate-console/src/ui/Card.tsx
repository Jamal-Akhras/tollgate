import type { ComponentChildren } from 'preact';
import styles from './Card.module.css';

type CardProps = { variant?: 'solid' | 'dashed'; children: ComponentChildren };

export function Card({ variant = 'solid', children }: CardProps) {
  const classes = [styles.card, variant === 'dashed' ? styles.dashed : ''].join(' ');
  return <div class={classes}>{children}</div>;
}
