import type { ComponentChildren } from 'preact';
import styles from './Card.module.css';

type CardProps = { variant?: 'solid' | 'dashed'; children: ComponentChildren };

export function Card({ variant = 'solid', children }: CardProps) {
  return <div class={`${styles.card} ${variant === 'dashed' ? styles.dashed : ''}`}>{children}</div>;
}
