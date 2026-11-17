import { useEffect, useState } from 'preact/hooks';
import { Check, Copy } from './icons';
import styles from './CopyButton.module.css';

export function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) {
      return;
    }
    const timer = setTimeout(() => setCopied(false), 1500);
    return () => clearTimeout(timer);
  }, [copied]);

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <button
      type="button"
      class={styles.button}
      aria-label={copied ? 'Copied' : 'Copy'}
      onClick={copy}
    >
      {copied ? <Check size={14} /> : <Copy size={14} />}
    </button>
  );
}
