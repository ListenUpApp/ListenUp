// Boots the PRODUCTION bundle from an INSECURE origin and fails if the app does not run.
//
// ⛔ The gate that was missing. ListenUp shipped a web client that refused to start on plain
// http — the deployment this project recommends — and three separate checks all passed it:
// `curl` never executes the bundle, the release smoke only asserts the HTML and assets are
// served, and `prod-boot.mjs` boots through `resolvedUrls.local`, i.e. `http://localhost`, which
// browsers treat as a *trustworthy* origin. Every gate tested the bytes; none tested the app
// where it actually runs.
//
// So this one deliberately uses `resolvedUrls.network` — the LAN address. `localhost` and
// `127.0.0.1` are both "potentially trustworthy" per the spec and would silently re-test the
// passing case, which is the trap that produced this file.
import { chromium } from 'playwright'
import { preview } from 'vite'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
// `host: true` binds 0.0.0.0 so vite reports a non-loopback URL at all.
const server = await preview({ root, preview: { port: 0, host: true } })
const network = (server.resolvedUrls.network ?? [])[0]

if (!network) {
  console.log('RESULT: SKIP (no non-loopback address on this machine)')
  await server.close()
  process.exit(0)
}
const base = network.replace(/\/$/, '')

const EXPECTED_WITHOUT_A_SERVER = /WebSocket connection to '[^']*\/api\/[^']*' failed/
// The browser announcing it ignored COOP because the origin is untrustworthy. That is not a
// fault — it is this test's whole premise, logged. The server sends the header correctly and the
// browser is right to drop it here; the app's answer is the in-memory fallback below.
const EXPECTED_ON_AN_INSECURE_ORIGIN = /Cross-Origin-(Opener|Embedder)-Policy header has been ignored/
const isNoise = (t) => EXPECTED_WITHOUT_A_SERVER.test(t) || EXPECTED_ON_AN_INSECURE_ORIGIN.test(t)

const browser = await chromium.launch()
const page = await browser.newPage()
const errors = []
page.on('pageerror', (e) => {
  if (!isNoise(String(e))) errors.push(String(e))
})
page.on('console', (m) => {
  if (m.type() === 'error' && !isNoise(m.text())) errors.push(m.text())
})

await page.goto(base, { waitUntil: 'load' })

// Proves the premise before asserting anything about it: if this origin were trustworthy the
// rest of the test would pass for the wrong reason, exactly as prod-boot.mjs did.
const secure = await page.evaluate(() => window.isSecureContext)
if (secure) {
  console.log(`RESULT: FAIL — ${base} is a secure context, so this tests nothing`)
  await browser.close()
  await server.close()
  process.exit(1)
}

// The app must get to a usable screen, not hang on "Checking your session…" (`.auth-boot`).
//
// ⛔ What this does NOT prove, measured rather than assumed: that the database opened. Sabotage
// with the worker's fallback removed still reaches `.auth-form` and still passes here, because
// the auth state resolves from localStorage-backed preferences, not from Room — and a database
// that fails to open raises nothing a page listener can see. That silence is why this class of
// bug hides. The VFS choice is therefore tested directly in `worker-vfs.test.mjs`; this file's
// job is narrower and stated honestly: on an insecure origin the app must RUN and must SAY what
// it lost, which is precisely the regression that shipped.
await page.waitForSelector('.auth-form', { timeout: 30_000 }).catch(() => null)
const stuckOnBoot = (await page.$('.auth-boot')) !== null
const reachedScreen = (await page.$('.auth-form')) !== null
const text = (await page.textContent('#app')) ?? ''

// And it must say what it cannot do. Silence would leave a reader wondering why their library
// rebuilds on every visit.
const banner = await page.textContent('.lapse.is-hint').catch(() => null)
// ⛔ `.luw` is WebAppSurface, which AuthGate applies — so its presence proves the APP rendered,
// not merely the banner. Read before the browser closes, obviously.
const appRendered = (await page.$('.luw')) !== null

await browser.close()
await server.close()

for (const e of errors) console.log(`  ERROR  ${e}`)
console.log(`origin:  ${base} (isSecureContext=false)`)
console.log(`#app:    ${JSON.stringify(text.slice(0, 90))}`)
console.log(`surface: ${appRendered}  usable-screen: ${reachedScreen}  stuck-on-boot: ${stuckOnBoot}`)
console.log(`banner:  ${JSON.stringify((banner ?? '').slice(0, 90))}`)

// Without the surface check the banner alone would satisfy this test, and a dead app underneath
// a tidy explanation is the exact failure shape being guarded against.
const ok = errors.length === 0 && appRendered && reachedScreen && !stuckOnBoot && (banner ?? '').length > 0
if (!appRendered) console.log('  the banner rendered but the app under it did not')
if (stuckOnBoot || !reachedScreen) {
  console.log('  the app never got past "Checking your session…"')
}
if (!banner) console.log('  the app started but never said storage is not persistent')
console.log(ok ? 'RESULT: PASS' : 'RESULT: FAIL')
process.exit(ok ? 0 : 1)
