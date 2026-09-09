// Boots the PRODUCTION bundle in a real browser and fails if it does not render.
//
// Kotlin/JS DCE only runs for the production executable, and nothing else in this repo ever
// loads it: the Kotest suite is compiled from jsTest, which has no production variant. A
// declaration DCE wrongly eliminates — a Koin binding reached only reflectively, a serializer,
// an `external` the optimiser cannot see through — fails here and nowhere else.
import { chromium } from 'playwright'
import { preview } from 'vite'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const server = await preview({ root, preview: { port: 0 } })
const base = server.resolvedUrls.local[0].replace(/\/$/, '')

// No server is started here — this gate is about the bundle, not the backend — so the app's
// first act, dialling the RPC socket, is expected to fail. That one error is noise; every other
// one is the signal this script exists to catch, so the filter is deliberately this narrow.
const EXPECTED_WITHOUT_A_SERVER = /WebSocket connection to '[^']*\/api\/[^']*' failed/

const browser = await chromium.launch()
const page = await browser.newPage()
const errors = []
const record = (text) => {
  if (!EXPECTED_WITHOUT_A_SERVER.test(text)) errors.push(text)
}
page.on('pageerror', (e) => record(String(e)))
page.on('console', (m) => {
  if (m.type() === 'error') record(m.text())
})

await page.goto(base, { waitUntil: 'load' })
// The app renders into #app. Anything inside it means the composition ran; empty means the
// bundle loaded and then did nothing, which is exactly how a bad DCE pass presents.
await page.waitForFunction(() => document.querySelector('#app')?.childElementCount > 0, null, {
  timeout: 30_000,
})
const text = await page.textContent('#app')

await browser.close()
await server.close()

for (const e of errors) console.log(`  ERROR  ${e}`)
console.log(`#app text: ${JSON.stringify((text ?? '').slice(0, 120))}`)
const ok = errors.length === 0 && (text ?? '').trim().length > 0
console.log(ok ? 'RESULT: PASS' : 'RESULT: FAIL')
process.exit(ok ? 0 : 1)
