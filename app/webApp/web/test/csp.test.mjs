// The Content-Security-Policy is kept in step in three places: the Ktor route that serves the
// bundle in production, the Vite preview server every browser gate boots through, and the hash of
// the one inline script in index.html. Nothing but these tests notices when they drift: the gates
// would keep passing under a policy that production does not send.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const read = (relative) => readFileSync(resolve(here, relative), 'utf8')

/** The quoted strings between `startMarker` and the next `endMarker`: the policy's directives. */
const directivesAfter = (source, startMarker, endMarker) => {
  const start = source.indexOf(startMarker)
  assert.notEqual(start, -1, `${startMarker} not found`)
  const body = source.slice(start + startMarker.length, source.indexOf(endMarker, start))
  return [...body.matchAll(/"([^"]*)"/g)].map((m) => m[1])
}

const vitePolicy = () =>
  directivesAfter(read('../vite.config.ts'), 'const contentSecurityPolicy = [', '].join')

const ktorPolicy = () =>
  directivesAfter(
    read('../../../../server/src/commonMain/kotlin/com/calypsan/listenup/server/routes/WebAppRoutes.kt'),
    'private val ContentSecurityPolicy =\n    listOf(',
    ').joinToString',
  )

test('the policy is read from both files, or the parity check below proves nothing', () => {
  assert.ok(vitePolicy().length >= 10, 'vite.config.ts policy not found')
  assert.ok(ktorPolicy().length >= 10, 'WebAppRoutes.kt policy not found')
})

test('Ktor and the preview server send the same policy', () => {
  assert.deepEqual(ktorPolicy(), vitePolicy())
})

test('the inline theme script is allowed by exactly the hash of the script index.html contains', () => {
  const html = read('../index.html')
  const inline = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)]
  assert.equal(inline.length, 1, 'expected exactly one inline script in index.html')
  const hash = `'sha256-${createHash('sha256').update(inline[0][1]).digest('base64')}'`
  const scriptSrc = vitePolicy().find((d) => d.startsWith('script-src '))
  assert.ok(
    scriptSrc?.split(' ').includes(hash),
    `the inline script in index.html hashes to ${hash}; update script-src in vite.config.ts and WebAppRoutes.kt`,
  )
})

test('script-src never allows string evaluation or inline script by blanket', () => {
  const scriptSrc = vitePolicy().find((d) => d.startsWith('script-src ')) ?? ''
  const sources = scriptSrc.split(' ')
  assert.ok(!sources.includes("'unsafe-eval'"))
  assert.ok(!sources.includes("'unsafe-inline'"))
})
