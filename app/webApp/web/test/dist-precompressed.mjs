// The webBundle gate's proof that precompression actually ran: every script in dist/assets ships
// with its brotli and gzip siblings, and the largest one — the Kotlin app — is reported by size.
// Without this, a build that silently skipped the step would still ship, just 10x heavier.

import { readdirSync, statSync, existsSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const assets = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'dist', 'assets')
const scripts = readdirSync(assets).filter((name) => name.endsWith('.js'))
const missing = scripts.flatMap((name) =>
  ['.br', '.gz'].filter((suffix) => !existsSync(join(assets, name + suffix))).map((suffix) => name + suffix),
)

const size = (name) => (existsSync(join(assets, name)) ? statSync(join(assets, name)).size : NaN)
const main = scripts.reduce((a, b) => (size(a) >= size(b) ? a : b))
const mb = (bytes) => `${(bytes / 1024 / 1024).toFixed(2)} MB`
console.log(`main script ${main}: raw ${mb(size(main))}, gzip ${mb(size(`${main}.gz`))}, brotli ${mb(size(`${main}.br`))}`)

for (const name of missing) console.log(`  MISSING  ${name}`)
console.log(missing.length === 0 ? 'RESULT: PASS' : 'RESULT: FAIL')
process.exit(missing.length === 0 ? 0 : 1)
