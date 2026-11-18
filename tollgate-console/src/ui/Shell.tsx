import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { get } from '../api/client';
import type { AdminInfo } from '../api/types';
import { route } from '../router';
import { SideNav } from './SideNav';
import styles from './Shell.module.css';
import { Toasts } from './Toast';
import { TopBar } from './TopBar';
import { useMedia } from './useMedia';

const key = 'tollgate.sidebar';

function storedSidebar(): boolean | null {
  try {
    const value = localStorage.getItem(key);
    return value === 'collapsed' ? true : value === 'open' ? false : null;
  } catch {
    return null;
  }
}

function section(name: string): string {
  return name === 'batch' || name === 'record' ? 'audit' : name;
}

export function Shell({ children }: { children: ComponentChildren }) {
  const narrow = useMedia('(max-width: 799px)');
  const medium = useMedia('(max-width: 1199px)');
  const [chosen, setChosen] = useState(storedSidebar);
  const [drawer, setDrawer] = useState(false);
  const [version, setVersion] = useState<string | null>(null);
  const toggle = useRef<HTMLButtonElement>(null);
  const main = useRef<HTMLElement>(null);
  const opened = useRef(false);

  const collapsed = !narrow && (chosen ?? medium);
  const current = section(route.value.name);
  const open = narrow && drawer;

  useEffect(() => {
    get<AdminInfo>('/info')
      .then((info) => setVersion(info.version))
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    if (!narrow) {
      setDrawer(false);
    }
  }, [narrow]);

  useEffect(() => {
    if (open) {
      opened.current = true;
      document.querySelector<HTMLElement>('nav a')?.focus();
    } else if (opened.current) {
      opened.current = false;
      toggle.current?.focus();
    }
  }, [open]);

  useEffect(() => {
    if (!open) {
      return;
    }
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setDrawer(false);
      }
    };
    document.addEventListener('keydown', escape);
    return () => document.removeEventListener('keydown', escape);
  }, [open]);

  function onToggle() {
    if (narrow) {
      setDrawer(!drawer);
      return;
    }
    const next = !collapsed;
    setChosen(next);
    try {
      localStorage.setItem(key, next ? 'collapsed' : 'open');
    } catch {}
  }

  const dotted = current === 'flow' || current === 'audit';

  return (
    <div class={styles.shell}>
      <a
        class={styles.skip}
        href="#main"
        onClick={(event) => {
          event.preventDefault();
          main.current?.focus();
        }}
      >
        Skip to main content
      </a>
      <TopBar
        version={version}
        narrow={narrow}
        collapsed={collapsed}
        drawerOpen={open}
        onToggle={onToggle}
        toggleRef={toggle}
      />
      <div class={styles.body}>
        {(!narrow || open) && (
          <SideNav
            current={current}
            collapsed={collapsed}
            drawer={open}
            onNavigate={() => setDrawer(false)}
          />
        )}
        {open && (
          <button
            type="button"
            class={styles.backdrop}
            aria-label="Close menu"
            onClick={() => setDrawer(false)}
          />
        )}
        <main id="main" tabIndex={-1} ref={main} class={`${styles.main} ${dotted ? 'canvas' : ''}`}>
          {dotted ? children : <div class={styles.content}>{children}</div>}
        </main>
      </div>
      <Toasts />
    </div>
  );
}
