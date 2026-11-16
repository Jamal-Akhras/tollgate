import { del, segment } from '../api/client';
import type { ConfigChange, ConfigView, KeyView } from '../api/types';
import { ConfirmEdit } from './ConfirmEdit';
import { runEdit } from './edit';
import { revokedKeyLines } from './yaml';

type RevokeKeyProps = {
  config: ConfigView;
  keyView: KeyView;
  onChanged: () => void;
  onClose: () => void;
};

export function RevokeKey({ config, keyView, onChanged, onClose }: RevokeKeyProps) {
  const last = config.keys.filter((item) => item.tenant === keyView.tenant).length === 1;
  const base =
    "Applications using this key get 401 as soon as this is written. Calls already running finish. It can't be undone: the key's hash is removed, and the key itself was never kept.";
  const warning = last
    ? `${base} It is ${keyView.tenant}'s last key, so ${keyView.tenant} can make no calls until you create another.`
    : base;
  return (
    <ConfirmEdit
      title={`Revoke key ${keyView.id}?`}
      lines={revokedKeyLines(keyView)}
      config={config}
      warning={warning}
      confirmLabel="Revoke key"
      onConfirm={() =>
        runEdit(() => del<ConfigChange>(`/keys/${segment(keyView.id)}`, config.version), { onChanged })
      }
      onClose={onClose}
    >
      <p>Stop {keyView.tenant} using key {keyView.id}.</p>
    </ConfirmEdit>
  );
}