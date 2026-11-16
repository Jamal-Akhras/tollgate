import styles from './Skeleton.module.css';

type SkeletonProps = { width?: string; height?: string };

export function Skeleton({ width = '100%', height = '16px' }: SkeletonProps) {
  return <div class={styles.skeleton} style={{ width, height }} aria-hidden="true" />;
}
