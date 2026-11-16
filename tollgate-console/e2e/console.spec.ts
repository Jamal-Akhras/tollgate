import { expect, test, type Page } from '@playwright/test';
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const token = process.env.TOLLGATE_E2E_TOKEN ?? '';
const key = process.env.TOLLGATE_E2E_KEY ?? '';
const folder = process.env.TOLLGATE_E2E_FOLDER ?? '';
const gateway = 'http://127.0.0.1:8080';

type Watched = { violations: string[]; errors: string[]; hosts: string[] };

async function watch(page: Page): Promise<Watched> {
  const watched: Watched = { violations: [], errors: [], hosts: [] };
  await page.exposeFunction('reportViolation', (text: string) => watched.violations.push(text));
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (event) => {
      const report = (window as unknown as { reportViolation: (text: string) => void })
        .reportViolation;
      report(`${event.violatedDirective} ${event.blockedURI}`);
    });
  });
  page.on('console', (message) => {
    const text = message.text();
    const refusedByTheApi = /Failed to load resource: the server responded with a status of (401|412)/;
    if (message.type() === 'error' && !refusedByTheApi.test(text)) {
      watched.errors.push(text);
    }
  });
  page.on('pageerror', (error) => watched.errors.push(error.message));
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (url.protocol.startsWith('http') && url.hostname !== '127.0.0.1') {
      watched.hosts.push(request.url());
    }
  });
  return watched;
}

async function send(content: string): Promise<number> {
  const answer = await fetch(`${gateway}/v1/chat/completions`, {
    method: 'POST',
    headers: { authorization: `Bearer ${key}`, 'content-type': 'application/json' },
    body: JSON.stringify({ model: 'qwen3', messages: [{ role: 'user', content }] }),
  });
  await answer.arrayBuffer();
  return answer.status;
}

async function expectNamed(page: Page) {
  const unnamed = await page.evaluate(() => {
    const nameOf = (element: Element) => {
      const labelled = element.getAttribute('aria-labelledby');
      const byId = labelled ? document.getElementById(labelled)?.textContent : '';
      return (
        element.getAttribute('aria-label') ||
        byId ||
        element.textContent ||
        element.getAttribute('title') ||
        ''
      ).trim();
    };
    return [...document.querySelectorAll('button, a[href]')]
      .filter((element) => nameOf(element) === '')
      .map((element) => element.outerHTML.slice(0, 120));
  });
  expect(unnamed).toEqual([]);
}

async function expectHeading(page: Page, name: string) {
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
  await expect(page.locator('main, body').first()).toMatchAriaSnapshot(
    `- heading "${name}" [level=1]`,
  );
  await expectNamed(page);
}

async function models(bearer: string): Promise<number> {
  const answer = await fetch(`${gateway}/v1/models`, {
    headers: { authorization: `Bearer ${bearer}` },
  });
  await answer.arrayBuffer();
  return answer.status;
}

async function openCheck(page: Page, name: RegExp) {
  await open(page, 'Flow');
  const pill = page.getByRole('button', { name });
  await expect(pill).toBeVisible();
  if ((await pill.getAttribute('aria-expanded')) !== 'true') {
    await pill.click();
  }
  await expect(page.getByRole('region', { name: /^Rule / })).toBeVisible();
}

async function open(page: Page, name: string) {
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name }).click();
}

test('the console shows what the server decided and proves it', async ({ page }) => {
  const watched = await watch(page);

  await test.step('sign in', async () => {
    await page.goto('/console/');
    await expect(page.getByRole('heading', { level: 1, name: 'Tollgate console' })).toBeVisible();
    await page.getByLabel('Admin token').fill('not-the-token');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('alert')).toContainText("That token isn't valid");
    await page.getByLabel('Admin token').fill(token);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expectHeading(page, 'Flow');
  });

  await test.step('the flow page shows the tenant, the policy and the provider', async () => {
    await expect(page.locator('[data-tenant="my-app"]')).toBeVisible();
    await expect(page.getByRole('region', { name: 'Policy default' })).toBeVisible();
    await expect(page.getByRole('button', { name: /request-size/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /data-class/ })).toBeVisible();
    await expect(page.getByText('ollama').first()).toBeVisible();
  });

  await test.step('tabbing reaches the tenants and the checks', async () => {
    await page.locator('body').click({ position: { x: 1, y: 1 } });
    const reached = new Set<string>();
    for (let press = 0; press < 80 && reached.size < 2; press += 1) {
      await page.keyboard.press('Tab');
      const where = await page.evaluate(() => {
        const element = document.activeElement;
        if (!element) {
          return '';
        }
        const outline = getComputedStyle(element).outlineStyle;
        if (element.hasAttribute('data-tenant')) {
          return outline === 'none' ? 'tenant without focus ring' : 'tenant';
        }
        if (element.hasAttribute('aria-expanded') && /data-class/.test(element.textContent ?? '')) {
          return outline === 'none' ? 'check without focus ring' : 'check';
        }
        return '';
      });
      if (where !== '') {
        reached.add(where);
      }
    }
    expect([...reached].sort()).toEqual(['check', 'tenant']);
  });

  await test.step('a refused call and an unreachable call show up', async () => {
    const refused = await send('my key is AKIAABCDEFGHIJKLMNOP');
    expect(refused).toBeGreaterThanOrEqual(400);
    expect(refused).toBeLessThan(500);
    const unreachable = await send('hello');
    expect(unreachable).toBeGreaterThanOrEqual(500);

    await expect(page.getByRole('link', { name: /^Refused/ })).toContainText(/Refused[1-9]\d*calls/, {
      timeout: 15_000,
    });

    await open(page, 'Activity');
    await expectHeading(page, 'Activity');
    const calls = page.getByRole('table', { name: 'Calls' });
    await expect(calls.getByRole('row').filter({ hasText: 'refused' })).toHaveCount(1, {
      timeout: 15_000,
    });
    await expect(calls.getByRole('row').filter({ hasText: 'failed' })).toHaveCount(1);
  });

  await test.step('the refused record is proved in the browser', async () => {
    await page.getByRole('table', { name: 'Calls' }).getByRole('row').filter({ hasText: 'refused' }).click();
    await page.getByRole('link', { name: 'Prove this record' }).click();
    await expect(page.getByText('Checked in your browser')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('equal', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('not equal')).toHaveCount(0);
    await expectNamed(page);
  });

  await test.step('the whole trail verifies', async () => {
    await open(page, 'Audit');
    await expectHeading(page, 'Audit');
    await page.getByRole('button', { name: 'Verify the whole trail' }).click();
    await expect(page.getByRole('status').filter({ hasText: 'intact' })).toContainText(
      'The audit trail is intact',
      { timeout: 30_000 },
    );
  });

  await test.step('reload reads the file again', async () => {
    await open(page, 'Config');
    await expectHeading(page, 'Config');
    await page.getByRole('button', { name: 'Reload', exact: true }).click();
    await expect(page.getByText('Nothing changed').first()).toBeVisible();
  });

  await test.step('the other pages have a heading and named controls', async () => {
    await open(page, 'Metrics');
    await expectHeading(page, 'Metrics');
    await open(page, 'Usage');
    await expectHeading(page, 'Usage');
  });

  await test.step('reduced motion stops the animation', async () => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await open(page, 'Flow');
    await expectHeading(page, 'Flow');
    await expect(page.locator('[data-tenant="my-app"]')).toBeVisible();
    const running = await page.evaluate(() => document.getAnimations().length);
    expect(running).toBe(0);
  });

  await test.step('the config page says edits are on', async () => {
    await open(page, 'Config');
    await expect(page.getByText('Edits: on')).toBeVisible();
  });

  await test.step('the guard switch writes the file and keeps what it replaced', async () => {
    await openCheck(page, /data-class/);
    await page.getByRole('switch', { name: 'Enforce data-class' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: 'Monitor data-class instead of enforcing it?' })).toBeVisible();
    await expect(dialog.getByText('This is the first change made from the console')).toBeVisible();
    await expect(dialog.getByText('While data-class only monitors')).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Cancel' })).toBeFocused();
    await dialog.getByRole('button', { name: "Monitor, don't enforce" }).click();
    await expect(page.getByText(/^Written\. Config version/).last()).toBeVisible();
    await expect(page.getByRole('button', { name: /data-class.*monitor/ })).toBeVisible();
    expect(existsSync(join(folder, 'tollgate.yaml.original'))).toBe(true);
    expect(readdirSync(join(folder, 'config-history'))).toHaveLength(2);

    await page.getByRole('switch', { name: 'Enforce data-class' }).click();
    await expect(dialog.getByRole('heading', { name: 'Enforce data-class again?' })).toBeVisible();
    await expect(dialog.getByText('This is the first change made from the console')).toHaveCount(0);
    await dialog.getByRole('button', { name: 'Enforce', exact: true }).click();
    await expect(page.getByRole('button', { name: /data-class.*monitor/ })).toHaveCount(0);
  });

  let created = '';
  await test.step('a key is created, shown once and works', async () => {
    await open(page, 'Config');
    await page.getByRole('button', { name: 'Create a key' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Create key' }).click();
    const shown = page.getByLabel('API key for my-app');
    await expect(shown).toBeVisible();
    created = await shown.inputValue();
    expect(created).toMatch(/^tg_[0-9A-Za-z]+_[0-9A-Za-z]+$/);
    expect(await models(created)).toBe(200);
    await page.getByRole('button', { name: 'Done' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page.locator('body')).not.toContainText(created);
    await expect(page.locator(`input[value="${created}"]`)).toHaveCount(0);
  });

  await test.step('revoking the key stops it', async () => {
    const id = created.split('_')[1] ?? '';
    await page.getByRole('button', { name: `Revoke key ${id}` }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('Applications using this key get 401')).toBeVisible();
    await dialog.getByRole('button', { name: 'Revoke key', exact: true }).click();
    await expect(page.getByRole('button', { name: `Revoke key ${id}` })).toHaveCount(0);
    expect(await models(created)).toBe(401);
    expect(await models(key)).toBe(200);
  });

  await test.step('a budget is set and shows', async () => {
    await page.getByRole('button', { name: 'Set a budget for my-app' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Limit').fill('5');
    await dialog.getByRole('button', { name: 'Set budget', exact: true }).click();
    await expect(page.getByText(/^Written\. Config version/).last()).toBeVisible();
    await expect(
      page.getByRole('table', { name: 'Tenants' }).getByRole('row').filter({ hasText: 'my-app' }),
    ).toContainText(/5(\.\d+)?\s*monthly/);
    await expect(page.getByRole('navigation', { name: 'Main' }).getByText(/my-app/)).toBeVisible({
      timeout: 45_000,
    });
  });

  await test.step('a change made by hand on disk stops the next edit until reload', async () => {
    const file = join(folder, 'tollgate.yaml');
    const original = readFileSync(file, 'utf8');
    expect(original).toContain('maxBytes: 1048576');
    const edited = original.replace('maxBytes: 1048576', 'maxBytes: 2097152');
    writeFileSync(file, edited);

    await openCheck(page, /data-class/);
    await page.getByRole('switch', { name: 'Enforce data-class' }).click();
    await page.getByRole('dialog').getByRole('button', { name: "Monitor, don't enforce" }).click();
    await expect(page.getByText(/was changed and has not been reloaded/).last()).toBeVisible();
    expect(readFileSync(file, 'utf8')).toBe(edited);

    await open(page, 'Config');
    await page.getByRole('button', { name: 'Reload', exact: true }).click();
    await expect(page.getByText(/^Reloaded: version/).last()).toBeVisible();

    await openCheck(page, /data-class/);
    await page.getByRole('switch', { name: 'Enforce data-class' }).click();
    await page.getByRole('dialog').getByRole('button', { name: "Monitor, don't enforce" }).click();
    await expect(page.getByText(/^Written\. Config version/).last()).toBeVisible();
    expect(readFileSync(file, 'utf8')).toContain('maxBytes: 2097152');
  });

  expect(watched.violations).toEqual([]);
  expect(watched.errors).toEqual([]);
  expect(watched.hosts).toEqual([]);
});
