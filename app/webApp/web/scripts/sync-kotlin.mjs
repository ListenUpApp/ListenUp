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
// to the Kotlin module that emits it. That is what lets the SAME Kotlin source drive both the
// webpack/karma lane and this one while the migration is in flight.

import { cp, rm, mkdir } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webRoot = resolve(here, '..')
const webAppRoot = resolve(webRoot, '..')

const variant = process.argv[2] ?? 'test'
const source =
  variant === 'main'
    ? resolve(webAppRoot, 'build/compileSync/js/main/developmentExecutable/kotlin')
    : resolve(webAppRoot, 'build/compileSync/js/test/testDevelopmentExecutable/kotlin')

if (!existsSync(source)) {
  console.error(
    `No Kotlin output at ${source}\n` +
      `Run the matching Gradle compile first, e.g.\n` +
      `  ./gradlew :app:webApp:jsTestTestDevelopmentExecutableCompileSync`,
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
