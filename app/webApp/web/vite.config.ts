import { defineConfig } from 'vite'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// The Kotlin compiler is the only thing KGP still owns: it emits ES modules and stops. Which
// variant those are — development or production, i.e. with DCE and minification or without —
// is a Kotlin-side choice made by `scripts/sync-kotlin.mjs`, which copies the chosen tree into
// `web/kotlin`. Vite takes over from there for dev server, bundling and tests, and the pages
// load the synced modules by path.

// OPFS needs SharedArrayBuffer, which the browser exposes only under cross-origin isolation,
// which requires the first two headers. The rest defend the tokens the client keeps in
// localStorage. This is the single place that sets them: one object feeds the dev server, the
// preview server and the Kotest page.
//
// Production hosting must send these too. When the built assets are served by Ktor, that route
// (WebAppRoutes.kt) needs the same set or the browser store silently loses OPFS.
const responseHeaders = {
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Embedder-Policy': 'require-corp',
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'same-origin',
  'X-Frame-Options': 'DENY',
}

// The policy the production page runs under. Derived from what the bundle actually loads: its own
// module scripts, the one inline theme script in index.html (allowed by hash, so script-src needs
// neither 'unsafe-inline' nor 'unsafe-eval'), the SQLite wasm, same-origin workers, and blob: for
// upload previews, downloads and hls.js's MediaSource. Images may come from other hosts because
// catalogue cover art is loaded by URL; images cannot run script. style-src keeps 'unsafe-inline'
// because Compose HTML writes element style attributes.
//
// ONE policy, THREE places that must agree: this array, the Ktor route (WebAppRoutes.kt), and the
// hash of the inline script in index.html. test/csp.test.mjs fails if any pair drifts. It is
// applied to preview only: the dev server injects its own inline client script, and a production
// policy there would break development for no benefit.
const contentSecurityPolicy = [
  "default-src 'self'",
  "script-src 'self' 'wasm-unsafe-eval' 'sha256-t35doVXbm2Sd99v4KIDSEw65Hyhn2ufZacjI2HErZWk='",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob: https: http:",
  "media-src 'self' blob:",
  "font-src 'self'",
  "connect-src 'self'",
  "worker-src 'self' blob:",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join('; ')

// The Ktor server in development. Production needs no proxy at all: Ktor serves the bundle, so
// the page and the API share an origin by construction. This makes development match that.
//
// `ws: true` is load-bearing, not decoration — the entire application surface is kotlinx.rpc over
// a WebSocket, so a proxy that only forwards HTTP would forward nothing that matters.
const apiTarget = process.env.LU_SERVER_URL ?? 'http://localhost:8080'

// The test-page build has no index.html of its own (the app and the test page need different Kotlin
// variants), yet WebThemeTest runs the inline pre-paint script FROM index.html, so the production
// build of the specs would otherwise find none. Emit the source file beside the page, as data only.
const emitIndexHtmlForSpecs = {
  name: 'emit-index-html-for-specs',
  generateBundle(this: { emitFile(file: object): void }) {
    if (process.env.LU_TEST_PAGE !== '1') return
    this.emitFile({
      type: 'asset',
      fileName: 'index.html',
      source: readFileSync(resolve(__dirname, 'index.html'), 'utf8'),
    })
  },
}

export default defineConfig({
  plugins: [emitIndexHtmlForSpecs],
  resolve: {
    alias: {
      // The SQLite worker's source lives in app/webApp/worker/, OUTSIDE this project root, where
      // Node resolution walks up and never reaches web/node_modules. sync-kotlin.mjs copies it
      // into kotlin/ before a run; this alias pins its bare import of @sqlite.org/sqlite-wasm to
      // this project's installed copy wherever the worker is resolved from.
      '@sqlite.org/sqlite-wasm': resolve(
        __dirname,
        'node_modules/@sqlite.org/sqlite-wasm',
      ),
    },
  },
  server: {
    headers: responseHeaders,
    // The Kotlin output and the SQLite worker both live outside this project root, so Vite's
    // file-serving allowlist is widened to the whole :app:webApp directory.
    fs: { allow: [resolve(__dirname, '..')] },
    proxy: {
      '/api': { target: apiTarget, ws: true, changeOrigin: true },
      '/healthz': { target: apiTarget, changeOrigin: true },
    },
  },
  preview: { headers: { ...responseHeaders, 'Content-Security-Policy': contentSecurityPolicy } },
  build: {
    rollupOptions: {
      // The Kotest page is a build input, not just a dev-server page, so `vite build` output can
      // be verified by the real specs rather than a smoke check — dev serves modules as authored,
      // while the build path is where the worker gets chunked and the .wasm sidecar emitted.
      //
      // It is opt-in because the app and the test page need DIFFERENT Kotlin variants synced into
      // kotlin/ (main vs test), so building both at once cannot work: whichever variant is not
      // synced fails to resolve. `LU_TEST_PAGE=1` selects the test build.
      input:
        process.env.LU_TEST_PAGE === '1'
          ? { kotest: resolve(__dirname, 'test/kotest.html') }
          : { index: resolve(__dirname, 'index.html') },
    },
  },
  // @sqlite.org/sqlite-wasm ships its own worker and .wasm sidecar; excluding it from
  // dep-optimisation keeps Vite from rewriting those into a form the worker can't load.
  optimizeDeps: { exclude: ['@sqlite.org/sqlite-wasm'] },
  worker: { format: 'es' },
})
