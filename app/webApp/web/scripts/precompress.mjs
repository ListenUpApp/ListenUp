// Writes a brotli (`.br`) and a gzip (`.gz`) sibling beside every compressible file in the built
// bundle, so the server can send the smallest one a browser accepts.
//
// ⛔ Build-time on purpose, not a Ktor plugin: `ktor-server-compression` publishes no linuxX64
// artifact, and the Kotlin/Native binary is what ships. It is the better design anyway — brotli at
// its slowest, densest setting costs ~20 s once per build instead of CPU on every request.
// `WebAppRoutes` (server) negotiates `Accept-Encoding` and serves these; see `WebBundle.kt`.
//
// Node's own zlib does both codecs, so this adds no dependency — nothing new to trust, pin or
// attribute in the licence manifest.
//
// Usage: node scripts/precompress.mjs <dir>

import { readdir, readFile, writeFile } from 'node:fs/promises'
import { join, extname } from 'node:path'
import { promisify } from 'node:util'
import { brotliCompress, gzip, constants } from 'node:zlib'

const brotli = promisify(brotliCompress)
const gz = promisify(gzip)

// Text and wasm. woff2 and png are already compressed — a second pass only adds bytes.
export const COMPRESSIBLE = new Set(['.js', '.mjs', '.css', '.html', '.svg', '.json', '.wasm'])

const encoders = {
  '.br': (bytes) =>
    brotli(bytes, {
      params: {
        [constants.BROTLI_PARAM_QUALITY]: constants.BROTLI_MAX_QUALITY,
        // A 16 MB window: the main script is ~16 MB, and the default 4 MB window leaves most of
        // its repetition out of reach. 24 is the largest the brotli spec lets a browser decode.
        [constants.BROTLI_PARAM_LGWIN]: 24,
        [constants.BROTLI_PARAM_SIZE_HINT]: bytes.length,
      },
    }),
  '.gz': (bytes) => gz(bytes, { level: constants.Z_BEST_COMPRESSION }),
}

async function* filesUnder(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) yield* filesUnder(path)
    else if (entry.isFile()) yield path
  }
}

/**
 * Precompresses every compressible file under [dir]. A variant is written only when it is smaller
 * than the original — the server treats a variant's presence as "this is better", so a larger one
 * must not exist. Returns one record per original file considered.
 */
export async function precompress(dir) {
  const originals = []
  for await (const path of filesUnder(dir)) {
    if (COMPRESSIBLE.has(extname(path))) originals.push(path)
  }
  return Promise.all(
    originals.map(async (path) => {
      const bytes = await readFile(path)
      const written = {}
      for (const [suffix, encode] of Object.entries(encoders)) {
        const encoded = await encode(bytes)
        if (encoded.length < bytes.length) {
          await writeFile(path + suffix, encoded)
          written[suffix] = encoded.length
        }
      }
      return { path, size: bytes.length, written }
    }),
  )
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const dir = process.argv[2]
  if (!dir) {
    console.error('usage: precompress.mjs <dir>')
    process.exit(1)
  }
  const results = await precompress(dir)
  const variants = results.reduce((n, r) => n + Object.keys(r.written).length, 0)
  console.log(`precompressed ${results.length} files into ${variants} variants under ${dir}`)
}
