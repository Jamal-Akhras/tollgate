import { useEffect, useState } from 'preact/hooks';

export function tokenValue(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

export function useTheme(): string {
  const root = document.documentElement;
  const [theme, setTheme] = useState(root.dataset.theme ?? '');
  useEffect(() => {
    const watch = new MutationObserver(() => setTheme(root.dataset.theme ?? ''));
    watch.observe(root, { attributes: true, attributeFilter: ['data-theme'] });
    return () => watch.disconnect();
  }, [root]);
  return theme;
}
