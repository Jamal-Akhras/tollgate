import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const admin = 'http://127.0.0.1:8081/admin/v1/info';

function jarPath() {
  if (process.env.TOLLGATE_JAR) {
    return process.env.TOLLGATE_JAR;
  }
  const target = resolve(here, '..', '..', 'tollgate-server', 'target');
  const jar = readdirSync(target).find(
    (name) => name.endsWith('.jar') && !name.endsWith('.original') && !name.includes('sources'),
  );
  if (!jar) {
    throw new Error(`No server jar in ${target}. Build tollgate-server first.`);
  }
  return join(target, jar);
}

function environment(folder) {
  const env = { ...process.env };
  for (const line of readFileSync(join(folder, 'tollgate.env'), 'utf8').split('\n')) {
    const at = line.indexOf('=');
    if (at > 0) {
      env[line.slice(0, at)] = line.slice(at + 1).trim();
    }
  }
  return env;
}

async function waitForAdmin(server, log) {
  const deadline = Date.now() + 90_000;
  while (Date.now() < deadline) {
    if (server.exitCode !== null) {
      throw new Error(`The server stopped with ${server.exitCode}:\n${log()}`);
    }
    const answer = await fetch(admin).catch(() => null);
    if (answer && answer.status < 500) {
      return;
    }
    await new Promise((done) => setTimeout(done, 500));
  }
  throw new Error(`The server did not answer in 90 seconds:\n${log()}`);
}

export default async function start() {
  const jar = jarPath();
  const folder = mkdtempSync(join(tmpdir(), 'tollgate-e2e-'));
  const init = spawnSync('java', ['-jar', jar, 'init'], { cwd: folder, encoding: 'utf8' });
  if (init.status !== 0) {
    throw new Error(`init failed:\n${init.stdout}\n${init.stderr}`);
  }
  const key = /tg_[0-9A-Za-z]+_[0-9A-Za-z]+/.exec(init.stdout)?.[0];
  const env = environment(folder);
  const token = env.TOLLGATE_ADMIN_TOKEN;
  if (!key || !token) {
    throw new Error('init did not print a key or write an admin token');
  }

  let output = '';
  const server = spawn('java', ['-XX:+ExitOnOutOfMemoryError', '-jar', jar], {
    cwd: folder,
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  server.stdout.on('data', (chunk) => (output = (output + chunk).slice(-20_000)));
  server.stderr.on('data', (chunk) => (output = (output + chunk).slice(-20_000)));

  try {
    await waitForAdmin(server, () => output);
  } catch (failure) {
    server.kill();
    throw failure;
  }

  process.env.TOLLGATE_E2E_TOKEN = token;
  process.env.TOLLGATE_E2E_KEY = key;
  process.env.TOLLGATE_E2E_FOLDER = folder;

  return async () => {
    if (server.exitCode === null) {
      const stopped = new Promise((done) => server.once('exit', done));
      server.kill();
      await stopped;
    }
    rmSync(folder, { recursive: true, force: true });
  };
}
