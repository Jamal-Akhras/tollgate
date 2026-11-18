import { ApiError } from '../api/client';
import type { ConfigChange, ConfigView, CreatedKey } from '../api/types';
import { showToast } from '../ui/Toast';

export function editsOn(config: ConfigView): boolean {
  return config.edits === 'managed';
}

export async function runEdit<T extends ConfigChange | CreatedKey>(
  send: () => Promise<T>,
  onChanged: () => void,
): Promise<T | undefined> {
  try {
    const result = await send();
    if ('changed' in result && !result.changed) {
      showToast('Nothing to change', 'success');
    } else {
      showToast(`Written. Config version ${result.version.slice(0, 8)}…`, 'success');
    }
    onChanged();
    return result;
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 412) {
      showToast(failure.message, 'error');
    } else if (
      failure instanceof ApiError &&
      failure.status === 409 &&
      failure.code === 'edits_off'
    ) {
      showToast('Edits were switched off on the server.', 'error');
    } else {
      throw failure;
    }
    onChanged();
    return undefined;
  }
}
