import { defineConfig } from 'vitest/config';

export default defineConfig({
  base: './',
  build: {
    outDir: 'target/classes/META-INF/tollgate-console',
    emptyOutDir: true,
    target: 'es2022',
    assetsInlineLimit: 0,
  },
  server: {
    proxy: {
      '/admin': 'http://127.0.0.1:8081',
    },
  },
  test: {
    environment: 'happy-dom',
  },
});
