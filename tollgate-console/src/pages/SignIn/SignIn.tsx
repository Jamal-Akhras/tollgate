import { useRef, useState } from 'preact/hooks';
import { ApiError, signInWith } from '../../api/client';
import { navigate, route } from '../../router';
import { Card } from '../../ui/Card';
import { TollgateMark } from '../../ui/icons';
import styles from './SignIn.module.css';

function describe(error: unknown): string {
  if (error instanceof ApiError && error.status === 401) {
    return "That token isn't valid";
  }
  return error instanceof Error ? error.message : 'Something went wrong';
}

export function SignIn() {
  const [value, setValue] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [disabled, setDisabled] = useState(false);
  const [busy, setBusy] = useState(false);
  const field = useRef<HTMLInputElement>(null);

  async function submit(event: Event) {
    event.preventDefault();
    if (busy || value === '') {
      return;
    }
    setBusy(true);
    setError(null);
    setDisabled(false);
    try {
      await signInWith(value);
      if (route.value.name === 'sign-in') {
        navigate('flow');
      }
    } catch (failure) {
      if (failure instanceof ApiError && failure.code === 'admin_disabled') {
        setDisabled(true);
      } else {
        setError(describe(failure));
      }
      field.current?.focus();
    } finally {
      setBusy(false);
    }
  }

  return (
    <div class={styles.page}>
      <div class={styles.card}>
        <Card>
          <form class={styles.form} onSubmit={submit}>
            <div class={styles.title}>
              <span class={styles.mark}>
                <TollgateMark size={28} />
              </span>
              <h1 class={styles.heading}>Tollgate console</h1>
            </div>
            {disabled && (
              <p class={styles.error} role="alert">
                The admin API is off. Set TOLLGATE_ADMIN_TOKEN to a value of at least 32
                characters, restart Tollgate, and sign in with it.
              </p>
            )}
            <label class={styles.label} for="admin-token">
              Admin token
            </label>
            <input
              id="admin-token"
              ref={field}
              class={styles.input}
              type="password"
              autocomplete="off"
              value={value}
              aria-invalid={error ? true : undefined}
              aria-describedby={error ? 'admin-token-error' : undefined}
              onInput={(event) => setValue(event.currentTarget.value)}
            />
            {error && (
              <p class={styles.error} id="admin-token-error" role="alert">
                {error}
              </p>
            )}
            <button type="submit" class={styles.button} disabled={busy}>
              Sign in
            </button>
            <p class={styles.hint}>
              The value of TOLLGATE_ADMIN_TOKEN. <code>tollgate init</code> wrote it to
              tollgate.env.
            </p>
          </form>
        </Card>
      </div>
    </div>
  );
}
