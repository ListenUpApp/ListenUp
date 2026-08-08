// Runs the compiled Kotest bundle in a real browser and translates its TeamCity service
// messages into a pass/fail verdict — the whole of what karma was doing for this project.
//
// Deliberately NOT Vitest: Kotest 6's JS engine owns test registration and reporting itself,
// so a second framework would only wrap N Kotest tests in 1 opaque Vitest test and discard the
// TeamCity stream. Playwright supplies the browser and the console; that is the actual gap.

import { chromium } from 'playwright'
import { createServer } from 'vite'

// Start our own Vite server rather than assuming a dev server is already up. A harness that
// silently depends on ambient state is a harness that passes on a developer's machine and dies
// in CI — and one that could just as easily "pass" against a stale server serving old code.
const server = await createServer({ server: { port: 0 } })
await server.listen()
const BASE = server.resolvedUrls.local[0].replace(/\/$/, '')

const browser = await chromium.launch()
const page = await browser.newPage()

const started = []
const failed = []
const finished = []
const pageErrors = []

// TeamCity service messages look like: ##teamcity[testFailed name='x' message='y']
const parse = (text) => {
  const m = /##teamcity\[(\w+)\s(.*)\]$/.exec(text.trim())
  if (!m) return
  const [, kind, rest] = m
  const name = /name='((?:[^'|]|\|.)*)'/.exec(rest)?.[1] ?? '(unnamed)'
  if (kind === 'testStarted') started.push(name)
  else if (kind === 'testFailed') failed.push({ name, detail: rest })
  else if (kind === 'testFinished') finished.push(name)
}

page.on('console', (m) => parse(m.text()))
page.on('pageerror', (e) => pageErrors.push(String(e)))

await page.goto(`${BASE}/test/kotest.html`, { waitUntil: 'load' })
// The engine runs asynchronously after import; give it room, then read what it emitted.
await page.waitForTimeout(15_000)
await browser.close()
await server.close()

console.log(`started:  ${started.length}`)
console.log(`finished: ${finished.length}`)
console.log(`failed:   ${failed.length}`)
for (const f of failed) console.log(`  FAILED  ${f.name}`)
if (pageErrors.length) {
  console.log('page errors:')
  for (const e of pageErrors) console.log(`  ${e}`)
}

// A run that started zero tests is a broken harness, not a green suite — the exact way this
// lane lies. Treat it as failure.
const ok = started.length > 0 && failed.length === 0
console.log(ok ? 'RESULT: PASS' : 'RESULT: FAIL')
process.exit(ok ? 0 : 1)
