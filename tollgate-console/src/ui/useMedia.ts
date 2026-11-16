import { useEffect, useState } from 'preact/hooks';

export function useMedia(query: string): boolean {
  const [matches, setMatches] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const list = window.matchMedia(query);
    setMatches(list.matches);
    const listen = (event: { matches: boolean }) => setMatches(event.matches);
    list.addEventListener('change', listen);
    return () => list.removeEventListener('change', listen);
  }, [query]);
  return matches;
}
