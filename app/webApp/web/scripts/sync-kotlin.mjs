// Copies KGP's emitted ES modules into this project, and the SQLite worker alongside them.
//
// The copy is not laziness — it is what makes bare-specifier resolution work. Vite resolves a
// bare import (`ws`, `@js-joda/core`, `@sqlite.org/sqlite-wasm`) by walking up from the
// IMPORTING file's directory. Left in build/compileSync, the Kotlin modules walk up through
// app/webApp/ and never reach web/node_modules, so every one of those imports 500s. Inside the
// project root they resolve normally.
//
// The worker lands at kotlin/sqlite-wasm-worker/worker.js specifically so that the specifier
// Kotlin emits — new URL("sqlite-wasm-worker/worker.js", import.meta.url) — resolves relative
// to the Kotlin module that emits it, with no bundler configuration on either side.

import { cp, rm, mkdir } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webRoot = resolve(here, '..')
const webAppRoot = resolve(webRoot, '..')

// Which Kotlin output each variant means. `main-prod` is the one `pnpm build` uses:
// developmentExecutable skips DCE and minification entirely, so shipping it means shipping
// every unreachable declaration in the graph.
const VARIANTS = {
  main: ['build/compileSync/js/main/developmentExecutable/kotlin', 'jsDevelopmentExecutableCompileSync'],
  'main-prod': ['build/compileSync/js/main/productionExecutable/kotlin', 'jsProductionExecutableCompileSync'],
  test: ['build/compileSync/js/test/testDevelopmentExecutable/kotlin', 'jsTestTestDevelopmentExecutableCompileSync'],
}

const variant = process.argv[2] ?? 'test'
const entry = VARIANTS[variant]
if (!entry) {
  console.error(`Unknown variant "${variant}". Expected one of: ${Object.keys(VARIANTS).join(', ')}`)
  process.exit(1)
}
const [relativeSource, gradleTask] = entry
const source = resolve(webAppRoot, relativeSource)

if (!existsSync(source)) {
  console.error(
    `No Kotlin output at ${source}\n` +
      `Run the matching Gradle compile first:\n` +
      `  ./gradlew :app:webApp:${gradleTask}`,
  )
  process.exit(1)
}

const dest = resolve(webRoot, 'kotlin')
await rm(dest, { recursive: true, force: true })
await cp(source, dest, { recursive: true })

const workerDest = resolve(dest, 'sqlite-wasm-worker')
await mkdir(workerDest, { recursive: true })
await cp(resolve(webAppRoot, 'worker/worker.js'), resolve(workerDest, 'worker.js'))

console.log(`synced ${variant} → web/kotlin`)
