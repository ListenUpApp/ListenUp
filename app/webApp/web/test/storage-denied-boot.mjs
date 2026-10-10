// Boots the PRODUCTION bundle in a browser that refuses storage, and fails if the page stays blank.
//
// Private mode and blocked site data make `localStorage` throw on access. The app may not be able
// to run in that state, but it must SAY so: a white page with a console trace is the worst way to
// report a condition the reader can act on. So unlike prod-boot.mjs this tolerates console errors
// (storage really is refused) and asserts the one thing that matters, that #app ends up with text.
import { chromium } from 'playwright'
import { preview } from 'vite'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const server = await preview({ root, preview: { port: 0 } })
const base = server.resolvedUrls.local[0].replace(/\/$/, '')

const browser = await chromium.launch()
const page = await browser.newPage()

await page.addInitScript(() => {
  const refuse = () => {
    throw new DOMException('The operation is insecure.', 'SecurityError')
  }
  Object.defineProperty(window, 'localStorage', { get: refuse })
})

await page.goto(base, { waitUntil: 'load' })
const rendered = await page
  .waitForFunction(() => (document.querySelector('#app')?.textContent ?? '').trim().length > 0, null, {
    timeout: 30_000,
  })
  .then(() => true)
  .catch(() => false)
const text = (await page.textContent('#app')) ?? ''

await browser.close()
await server.close()

console.log(`#app text: ${JSON.stringify(text.slice(0, 200))}`)
if (!rendered) console.log('  #app stayed empty: a browser that refuses storage got a blank page')
console.log(rendered ? 'RESULT: PASS' : 'RESULT: FAIL')
process.exit(rendered ? 0 : 1)
