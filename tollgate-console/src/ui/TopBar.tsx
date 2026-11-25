import type { Ref } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { signOut } from '../api/client';
import { route } from '../router';
import { tollgateOn } from '../state/preview';
import { setTheme, theme, type Theme } from '../state/theme';
import { ChevronsUpDown, Menu, SidebarToggle, TollgateMark } from './icons';
import { Segmented } from './Segmented';
import { Switch } from './Switch';
import styles from './TopBar.module.css';

type TopBarProps = {
  version: string | null;
  narrow: boolean;
  collapsed: boolean;
  drawerOpen: boolean;
  onToggle: () => void;
  toggleRef: Ref<HTMLButtonElement>;
};

const themes: { value: Theme; label: string }[] = [
  { value: 'system', label: 'System' },
  { value: 'dark', label: 'Dark' },
  { value: 'light', label: 'Light' },
];

function InstanceMenu({ version }: { version: string | null }) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const chip = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    const outside = (event: MouseEvent) => {
      if (event.target instanceof Node && !root.current?.contains(event.target)) {
        setOpen(false);
      }
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
        chip.current?.focus();
      }
    };
    document.addEventListener('mousedown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('mousedown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  return (
    <div class={styles.instance} ref={root}>
      <button
        type="button"
        class={styles.chip}
        aria-expanded={open}
        aria-controls="instance-menu"
        ref={chip}
        onClick={() => setOpen(!open)}
      >
        <span class={styles.letter} aria-hidden="true">
          {location.host.charAt(0).toUpperCase()}
        </span>
        <span class={styles.host}>
          <span class={styles.hostName}>{location.host}</span>
          {version && <span class={styles.version}>v{version}</span>}
        </span>
        <ChevronsUpDown size={14} />
      </button>
      {open && (
        <div class={styles.menu} id="instance-menu">
          <div class={styles.menuLabel}>Theme</div>
          <Segmented
            label="Theme"
            options={themes}
            value={theme.value}
            onChange={setTheme}
          />
          <button type="button" class={styles.signOut} onClick={signOut}>
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}

export function TopBar({
  version,
  narrow,
  collapsed,
  drawerOpen,
  onToggle,
  toggleRef,
}: TopBarProps) {
  const label = narrow ? 'Open menu' : collapsed ? 'Expand sidebar' : 'Collapse sidebar';
  return (
    <header class={styles.bar}>
      <div class={styles.left}>
        <span class={styles.mark}>
          <TollgateMark size={22} />
        </span>
        <span class={styles.name}>Tollgate</span>
        <button
          type="button"
          class={styles.toggle}
          aria-label={label}
          aria-expanded={narrow ? drawerOpen : !collapsed}
          ref={toggleRef}
          onClick={onToggle}
        >
          {narrow ? <Menu /> : <SidebarToggle />}
        </button>
      </div>
      <div class={styles.right}>
        <div class={styles.preview} data-slot="preview">
          {route.value.name === 'flow' && (
            <>
              <span class={styles.previewLabel}>With Tollgate</span>
              <Switch
                label="Preview this page without Tollgate"
                checked={tollgateOn.value}
                onChange={(on) => {
                  tollgateOn.value = on;
                }}
              />
            </>
          )}
        </div>
        <span class={styles.divider} aria-hidden="true" />
        <InstanceMenu version={version} />
      </div>
    </header>
  );
}
