// Runs the compiled Kotest bundle in a real browser and translates its TeamCity service
// messages into a pass/fail verdict — the whole of what karma was doing for this project.
//
// Deliberately NOT Vitest: Kotest 6's JS engine owns test registration and reporting itself,
// so a second framework would only wrap N Kotest tests in 1 opaque Vitest test and discard the
// TeamCity stream. Playwright supplies the browser and the console; that is the actual gap.

import { chromium } from 'playwright'
import { createServer, preview } from 'vite'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// The number of tests the suite is known to contain. A run that finds fewer has not "passed" —
// it has failed to discover something, which is the exact way this class of lane lies: the
// Kotlin/Native lane once ran 28 of 82 tests and reported green. The Gradle lanes carry
// discovered-test-count floors for this reason; so does this one.
//
// Raise it when specs are added. Lowering it needs a reason.
const MIN_TESTS = Number(process.env.KOTEST_MIN_TESTS ?? 9)

// Kotest's JS engine emits no "run finished" marker — `mainWrapper()` calls a suspend `main`
// with an empty continuation, so there is no promise to await either. Completion is therefore
// inferred from quiescence: the stream has stopped for IDLE_MS. That is strictly better than a
// fixed wait, which silently truncates a suite that outgrows it and reports the partial run as
// a pass. CEILING_MS only bounds a hang, and hitting it is a failure, not a result.
const IDLE_MS = 3_000
const CEILING_MS = 180_000

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')

// `external` points the suite at a server we did not start — used to run the very same specs
// against Ktor serving the built assets, which is the only way to prove the production path
// end to end. Vite serving dist/ and Ktor serving dist/ are different servers with different
// header and content-type behaviour; only one of them ships.
const arg = process.argv[2]
const mode = arg === 'preview' || arg === 'dev' ? arg : arg ? 'external' : 'dev'

// `root` is pinned rather than left to default: Vite resolves it from process.cwd(), so a
// runner invoked from anywhere but the project directory silently serves the wrong tree and
// 404s — which this harness would otherwise report as a zero-test run.
const server =
  mode === 'preview'
    ? await preview({ root, preview: { port: 0 } })
    : mode === 'dev'
      ? await createServer({ root, server: { port: 0 } }).then((s) => s.listen())
      : null
const BASE = server ? server.resolvedUrls.local[0].replace(/\/$/, '') : arg.replace(/\/$/, '')
console.log(`mode: ${mode}  base: ${BASE}`)

const browser = await chromium.launch()
const page = await browser.newPage()

const started = []
const failed = []
const finished = []
const ignored = []
const pageErrors = []
let lastMessageAt = Date.now()

// TeamCity service messages look like: ##teamcity[testFailed name='x' message='y']
const parse = (text) => {
  const m = /##teamcity\[(\w+)\s(.*)\]$/.exec(text.trim())
  if (!m) return
  const [, kind, rest] = m
  lastMessageAt = Date.now()
  const name = /name='((?:[^'|]|\|.)*)'/.exec(rest)?.[1] ?? '(unnamed)'
  if (kind === 'testStarted') started.push(name)
  else if (kind === 'testFailed') failed.push({ name, detail: rest })
  else if (kind === 'testFinished') finished.push(name)
  else if (kind === 'testIgnored') ignored.push(name)
}

page.on('console', (m) => parse(m.text()))
page.on('pageerror', (e) => {
  pageErrors.push(String(e))
  lastMessageAt = Date.now()
})

const startedAt = Date.now()
await page.goto(`${BASE}/test/kotest.html`, { waitUntil: 'load' })

let timedOut = false
for (;;) {
  await page.waitForTimeout(250)
  const now = Date.now()
  if (started.length > 0 && now - lastMessageAt > IDLE_MS) break
  if (now - startedAt > CEILING_MS) {
    timedOut = true
    break
  }
}

await browser.close()
await server?.close()

const dangling = started.length - finished.length - ignored.length

console.log(`started:  ${started.length}`)
console.log(`finished: ${finished.length}`)
console.log(`ignored:  ${ignored.length}`)
console.log(`failed:   ${failed.length}`)
for (const f of failed) console.log(`  FAILED  ${f.name}`)
if (pageErrors.length) {
  console.log('page errors:')
  for (const e of pageErrors) console.log(`  ${e}`)
}

const problems = []
if (timedOut) problems.push(`the run did not settle within ${CEILING_MS / 1000}s`)
if (started.length < MIN_TESTS) {
  problems.push(`discovered ${started.length} tests, expected at least ${MIN_TESTS}`)
}
if (dangling > 0) problems.push(`${dangling} test(s) started but never reported a result`)
if (failed.length > 0) problems.push(`${failed.length} test(s) failed`)

for (const p of problems) console.log(`PROBLEM: ${p}`)
console.log(problems.length === 0 ? 'RESULT: PASS' : 'RESULT: FAIL')
process.exit(problems.length === 0 ? 0 : 1)
