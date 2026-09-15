// Builds the licence manifest the web client serves, from the two halves of what it ships.
//
// ⛔ Deliberately NOT a copy of `app/sharedUI/.../aboutlibraries.json`. That manifest is the ANDROID
// dependency graph — Media3, Firebase, Play Services, bytedeco — and the browser loads none of it.
// Serving it here would attribute libraries this client never bundles, which on an attribution page
// is worse than having no page at all.
//
// Half one: the Kotlin/JS graph, exported by AboutLibraries from `:app:webApp` (68 libraries —
// kotlinx, Ktor, Room/SQLite, Compose HTML).
// Half two: npm production dependencies. `--prod` matters: vite, typescript and playwright build
// and test the bundle rather than being in it. The four that remain are the bare specifiers
// `sync-kotlin.mjs` exists to make resolvable, so they are in the bundle even though no
// hand-written source here imports them.
//
// Usage: node scripts/build-licences.mjs <kotlin-export.json> <output.json>

import { execFileSync } from 'node:child_process'
import { readFileSync, writeFileSync } from 'node:fs'

const [kotlinExportPath, outputPath] = process.argv.slice(2)
if (!kotlinExportPath || !outputPath) {
  console.error('usage: build-licences.mjs <kotlin-export.json> <output.json>')
  process.exit(1)
}

const here = new URL('..', import.meta.url).pathname

function npmLibraries() {
  const byLicence = JSON.parse(
    execFileSync('pnpm', ['licenses', 'list', '--prod', '--json'], { encoding: 'utf8', cwd: here }),
  )
  const out = []
  for (const [licence, packages] of Object.entries(byLicence)) {
    for (const pkg of packages) {
      // pnpm reports `versions` as an array — one entry per resolved version of the same package.
      const versions = Array.isArray(pkg.versions) ? pkg.versions : [pkg.version]
      for (const version of versions) {
        out.push({
          uniqueId: `npm:${pkg.name}`,
          artifactVersion: version,
          name: pkg.name,
          description: pkg.description || null,
          website: pkg.homepage || null,
          licenses: [licence],
        })
      }
    }
  }
  return out
}

function kotlinLibraries(exported) {
  // AboutLibraries keys its licences by hash and references them per library; flatten to the SPDX
  // identifier so the two halves describe a library the same way and the page needs one shape.
  //
  // ⛔ `spdxId`, not `name`. The record carries both — "Apache-2.0" and "Apache License 2.0" — and
  // pnpm reports the SPDX id. Taking `name` here made one licence render as two families.
  const licencesById = exported.licenses ?? {}
  return (exported.libraries ?? []).map((lib) => ({
    uniqueId: lib.uniqueId,
    artifactVersion: lib.artifactVersion ?? null,
    name: lib.name,
    description: lib.description || null,
    website: lib.website || null,
    licenses: (lib.licenses ?? []).map((id) => licencesById[id]?.spdxId ?? licencesById[id]?.name ?? id),
  }))
}

const libraries = [...kotlinLibraries(JSON.parse(readFileSync(kotlinExportPath, 'utf8'))), ...npmLibraries()]

// Sorted so the manifest is a function of the dependency graph and nothing else — the drift gate
// diffs it, and an unstable order would fail on every run.
libraries.sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()) || a.uniqueId.localeCompare(b.uniqueId))

writeFileSync(outputPath, `${JSON.stringify({ libraries }, null, 2)}\n`)
console.log(`wrote ${libraries.length} libraries to ${outputPath}`)
