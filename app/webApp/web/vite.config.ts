import { defineConfig } from 'vite'
import { resolve } from 'node:path'

// The Kotlin compiler is the only thing KGP still owns: it emits ES modules here and stops.
// Vite takes over from this directory for dev server, bundling and tests.
//
// Development vs production executable is a Kotlin-side distinction (DCE + minification), not
// a Vite one — point at whichever KGP last synced. `KOTLIN_OUT` lets CI pin the production
// tree explicitly.
const kotlinOut = resolve(
  __dirname,
  process.env.KOTLIN_OUT ??
    '../build/compileSync/js/main/developmentExecutable/kotlin',
)

// OPFS needs SharedArrayBuffer, which the browser exposes only under cross-origin isolation,
// which requires these two headers. This replaces BOTH webpack.config.d/coop-coep.js and
// karma.config.d/coop-coep.js — one place instead of two, and the same object feeds the dev
// server, the preview server and the Vitest browser provider.
//
// Production hosting must send these too. When the built assets are served by Ktor, that
// route needs the same pair or the browser store silently loses OPFS.
const crossOriginIsolation = {
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Embedder-Policy': 'require-corp',
}

export default defineConfig({
  resolve: {
    alias: {
      '@kotlin': kotlinOut,
      // worker/worker.js sits OUTSIDE this project root, so Node resolution walks up from
      // app/webApp/worker/ and never reaches web/node_modules — its bare import of
      // @sqlite.org/sqlite-wasm fails. Webpack papered over this by having the worker
      // masquerade as an npm package (webApp/worker/package.json + a Gradle npm() dependency);
      // an alias is the honest version and keeps ONE copy of the worker while both test lanes
      // coexist. When karma goes, the worker moves into web/src and this alias goes with it.
      '@sqlite.org/sqlite-wasm': resolve(
        __dirname,
        'node_modules/@sqlite.org/sqlite-wasm',
      ),
    },
  },
  server: {
    headers: crossOriginIsolation,
    // The Kotlin output and the SQLite worker both live outside this project root, so Vite's
    // file-serving allowlist is widened to the whole :app:webApp directory.
    fs: { allow: [resolve(__dirname, '..')] },
  },
  preview: { headers: crossOriginIsolation },
  // @sqlite.org/sqlite-wasm ships its own worker and .wasm sidecar; excluding it from
  // dep-optimisation keeps Vite from rewriting those into a form the worker can't load.
  optimizeDeps: { exclude: ['@sqlite.org/sqlite-wasm'] },
  worker: { format: 'es' },
})
