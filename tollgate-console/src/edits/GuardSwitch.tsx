import { useState } from 'preact/hooks';
import { put, segment } from '../api/client';
import type { ConfigChange, ConfigView, GuardMode } from '../api/types';
import { opensFor } from '../flow/describe';
import { Switch } from '../ui/Switch';
import { ConfirmEdit } from './ConfirmEdit';
import { editsOn, runEdit } from './edit';
import styles from './GuardSwitch.module.css';
import { HowToChange } from './HowToChange';
import { guardMode } from './snippets';
import { guardModeLines } from './yaml';

type GuardSwitchProps = {
  config: ConfigView;
  policy: string;
  index: number;
  onChanged: () => void;
};

function dialogFor(config: ConfigView, policy: string, type: string, mode: GuardMode['mode']) {
  if (mode === 'enforce') {
    return {
      title: `Enforce ${type} again?`,
      text: ['Calls it would refuse are refused again as soon as this is written.'],
      warning: undefined,
      confirm: 'Enforce',
    };
  }
  const users = config.tenants
    .filter((tenant) => tenant.policy === policy)
    .map((tenant) => tenant.name);
  const text = [
    'It will still run on every call and record what it would have refused, but it will refuse nothing.',
    `Policy ${policy} is used by ${users.length > 0 ? users.join(', ') : 'no tenant'}.`,
  ];
  const title = `Monitor ${type} instead of enforcing it?`;
  if (type !== 'data-class') {
    return { title, text, warning: undefined, confirm: 'Monitor' };
  }
  const opens = opensFor(config, policy);
  const reach =
    opens.length === 0
      ? 'Every provider in this config is in a region it allows, so nothing new becomes reachable today.'
      : `For policy ${policy} that means ${opens
          .map((open) => `${open.dataClass} data could go to ${open.regions.join(', ')}`)
          .join('; ')}.`;
  return {
    title,
    text,
    warning: `While data-class only monitors, requests can reach regions it would refuse. ${reach} Each call it would have refused is still recorded.`,
    confirm: "Monitor, don't enforce",
  };
}

export function GuardSwitch({ config, policy, index, onChanged }: GuardSwitchProps) {
  const [asking, setAsking] = useState(false);
  const guard = config.policies.find((item) => item.name === policy)?.guards[index];
  if (!guard) {
    return null;
  }
  const enforcing = guard.mode !== 'monitor';
  const mode: GuardMode['mode'] = enforcing ? 'monitor' : 'enforce';
  const ask = dialogFor(config, policy, guard.type, mode);

  return (
    <span class={styles.switch}>
      <Switch
        label={`Enforce ${guard.type}`}
        checked={enforcing}
        onWord="Enforcing"
        offWord="Monitoring"
        onChange={() => setAsking(true)}
      />
      {asking && editsOn(config) && (
        <ConfirmEdit
          title={ask.title}
          lines={guardModeLines(policy, index, guard.type, mode)}
          config={config}
          warning={ask.warning}
          confirmLabel={ask.confirm}
          onConfirm={() =>
            runEdit(
              () =>
                put<ConfigChange>(
                  `/policies/${segment(policy)}/guards/${index}/mode`,
                  { mode },
                  config.version,
                ),
              onChanged,
            )
          }
          onClose={() => setAsking(false)}
        >
          {ask.text.map((line) => (
            <p key={line}>{line}</p>
          ))}
        </ConfirmEdit>
      )}
      {asking && !editsOn(config) && (
        <HowToChange
          snippet={guardMode(policy, index, guard.type, mode)}
          onClose={() => setAsking(false)}
          onChanged={onChanged}
        />
      )}
    </span>
  );
}
