import { Banner } from './Banner';
import buttons from './button.module.css';

type RetryBannerProps = { error: Error; onRetry: () => void };

export function RetryBanner({ error, onRetry }: RetryBannerProps) {
  return (
    <Banner
      kind="error"
      action={
        <button type="button" class={buttons.button} onClick={onRetry}>
          Retry
        </button>
      }
    >
      {error.message}
    </Banner>
  );
}
