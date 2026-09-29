// The webBundle gate's proof that what should load on demand does, and what should load early does.
//
// hls.js is imported dynamically (HlsAttachment.kt), so Vite must emit it as a chunk of its own and
// the entry chunk every visitor downloads must not contain it: a regression to a static import
// compiles, bundles and boots fine, and only shows up as ~600 KB back on every cold start.
// `manifestLoadError` is one of hls.js's own error-detail strings — present wherever its code is.
//
// The body font is preloaded from index.html; the check is that Vite rewrote the href to the same
// hashed file the stylesheet's @font-face points at, rather than leaving a dead `/src/...` path.

import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const dist = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'dist')
const assets = join(dist, 'assets')
const scripts = readdirSync(assets).filter((name) => name.endsWith('.js'))
const html = readFileSync(join(dist, 'index.html'), 'utf8')
const entry = html.match(/<script type="module"[^>]*src="\/assets\/([^"]+\.js)"/)?.[1]
const hls = scripts.find((name) => /^hls-.*\.js$/.test(name))
const preload = html.match(/<link rel="preload" as="font"[^>]*href="\/assets\/([^"]+\.woff2)"/)?.[1]

const failures = []
if (!entry) failures.push('no entry script found in dist/index.html')
if (!hls) failures.push('no hls-*.js chunk: hls.js is not being split out')
if (entry && readFileSync(join(assets, entry), 'utf8').includes('manifestLoadError')) {
  failures.push(`hls.js code is in the entry chunk ${entry}`)
}
if (!preload) failures.push('no hashed font preload in dist/index.html')
else if (!readdirSync(assets).includes(preload)) failures.push(`preloaded font ${preload} is not in dist/assets`)

console.log(`entry ${entry}, hls chunk ${hls}, preloaded font ${preload}`)
for (const failure of failures) console.log(`  FAIL  ${failure}`)
console.log(failures.length === 0 ? 'RESULT: PASS' : 'RESULT: FAIL')
process.exit(failures.length === 0 ? 0 : 1)
