import type { ConfigView } from '../../api/types';
import { CodeBlock } from '../../ui/CodeBlock';
import styles from './FirstRun.module.css';

export function firstRunCommand(host: string, config: ConfigView, tenantName: string): string | null {
  const tenant = config.tenants.find((item) => item.name === tenantName);
  const model = config.models.find((item) => item.name === tenant?.models[0]);
  const provider = config.providers.find((item) => item.name === model?.provider);
  if (!model || !provider) {
    return null;
  }
  const origin = `http://${host}:8080`;
  if (provider.format === 'anthropic') {
    return [
      `curl ${origin}/anthropic/v1/messages \\`,
      '  -H "x-api-key: $TOLLGATE_KEY" \\',
      '  -H "anthropic-version: 2023-06-01" \\',
      '  -H "content-type: application/json" \\',
      `  -d '{"model": "${model.name}", "max_tokens": 64, "messages": [{"role": "user", "content": "Say hello"}]}'`,
    ].join('\n');
  }
  return [
    `curl ${origin}/v1/chat/completions \\`,
    '  -H "Authorization: Bearer $TOLLGATE_KEY" \\',
    '  -H "content-type: application/json" \\',
    `  -d '{"model": "${model.name}", "messages": [{"role": "user", "content": "Say hello"}]}'`,
  ].join('\n');
}

export function FirstRun({ config, tenant }: { config: ConfigView; tenant: string }) {
  const command = firstRunCommand(location.hostname, config, tenant);
  return (
    <section class={styles.panel} aria-label="First call">
      <h2 class={styles.title}>No calls yet. Send one:</h2>
      {command ? (
        <>
          <CodeBlock code={command} />
          <p class={styles.note}>8080 is the default gateway port.</p>
        </>
      ) : (
        <p class={styles.note}>{tenant} has no model in tollgate.yaml to send a call to.</p>
      )}
    </section>
  );
}
