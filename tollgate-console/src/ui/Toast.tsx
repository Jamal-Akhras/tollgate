import { signal } from '@preact/signals';
import { Cross } from './icons';
import styles from './Toast.module.css';

type ToastKind = 'success' | 'error';

type ToastItem = { id: number; message: string; kind: ToastKind };

const lifetimeMillis = 5000;

const toasts = signal<ToastItem[]>([]);
let next = 0;

function dismiss(id: number) {
  toasts.value = toasts.value.filter((toast) => toast.id !== id);
}

export function showToast(message: string, kind: ToastKind) {
  next += 1;
  const id = next;
  toasts.value = [...toasts.value, { id, message, kind }];
  setTimeout(() => dismiss(id), lifetimeMillis);
}

export function Toasts() {
  return (
    <div class={styles.region} role="status">
      {toasts.value.map((toast) => (
        <div key={toast.id} class={`${styles.toast} ${styles[toast.kind]}`}>
          <span class={styles.message}>{toast.message}</span>
          <button
            type="button"
            class={styles.close}
            aria-label="Dismiss"
            onClick={() => dismiss(toast.id)}
          >
            <Cross size={14} />
          </button>
        </div>
      ))}
    </div>
  );
}
