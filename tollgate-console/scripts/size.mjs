import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';

const outDir = 'target/classes/META-INF/tollgate-console';
const limit = 80000;

const html = readFileSync(join(outDir, 'index.html'), 'utf8');
const files = [...html.matchAll(/(?:src|href)="\.\/([^"]+\.(?:js|css))"/g)].map((m) => m[1]);

let total = gzipSync(html).length;
for (const file of files) {
  total += gzipSync(readFileSync(join(outDir, file))).length;
}

console.log(`first load: ${total} bytes gzipped (limit ${limit})`);
if (total > limit) {
  process.exit(1);
}
