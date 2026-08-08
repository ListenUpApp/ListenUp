// Dev-loop sanity page for the toolchain spike. It answers one question only: does the
// environment Vite serves still satisfy the browser store's preconditions?
//
// The real proof that the store works lives in the Kotlin specs — this page exists so a human
// running `pnpm dev` sees immediately whether the headers and the worker are wired up, without
// having to read a test report.

const lines: string[] = []
const report = (label: string, ok: boolean, detail = '') =>
  lines.push(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? `  — ${detail}` : ''}`)

report('crossOriginIsolated', globalThis.crossOriginIsolated === true)
report('SharedArrayBuffer', typeof SharedArrayBuffer === 'function')

// Vite resolves `new URL(..., import.meta.url)` for RELATIVE specifiers and emits the worker
// (plus the @sqlite.org/sqlite-wasm .wasm sidecar it imports) as its own chunk. Webpack also
// accepted a BARE specifier here — `new URL("sqlite-wasm-worker/worker.js", ...)` — which is
// why the worker currently has to masquerade as an npm package. Vite does not, and does not
// need it to: this is our own source file, so a relative path is both correct and simpler.
try {
  const worker = new Worker(new URL('../../worker/worker.js', import.meta.url), {
    type: 'module',
  })
  await new Promise<void>((resolve, reject) => {
    worker.addEventListener('error', (e) => reject(new Error(e.message)), { once: true })
    // The worker initialises sqlite3 asynchronously; nothing is posted until it is ready, so
    // surviving a turn of the event loop without an error event is the signal we want here.
    setTimeout(resolve, 1_000)
  })
  report('sqlite worker module resolves and starts', true)
  worker.terminate()
} catch (e) {
  report('sqlite worker module resolves and starts', false, String(e))
}

document.querySelector('#report')!.textContent = lines.join('\n')
