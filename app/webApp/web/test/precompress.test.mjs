// The build's precompression step. The server trusts a `.br`/`.gz` sibling to be the same file,
// smaller — so the two properties pinned here are "it decodes back to the original" and "it is
// only there when it is smaller".

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { brotliDecompressSync, gunzipSync } from 'node:zlib'
import { precompress } from '../scripts/precompress.mjs'

async function bundle() {
  const dir = await mkdtemp(join(tmpdir(), 'precompress-'))
  await mkdir(join(dir, 'assets'))
  await writeFile(join(dir, 'index.html'), '<!doctype html>'.padEnd(4096, ' <p>ListenUp</p>'))
  await writeFile(join(dir, 'assets', 'app.js'), 'export const x = 1;\n'.repeat(500))
  await writeFile(join(dir, 'assets', 'font.woff2'), 'already compressed'.repeat(100))
  // Too small to shrink: the codec's framing costs more than it saves.
  await writeFile(join(dir, 'assets', 'tiny.js'), 'x')
  return dir
}

test('every compressible file gets a brotli and a gzip sibling that decode to it', async () => {
  const dir = await bundle()
  await precompress(dir)
  for (const name of ['index.html', 'assets/app.js']) {
    const original = await readFile(join(dir, name))
    assert.deepEqual(brotliDecompressSync(await readFile(join(dir, `${name}.br`))), original)
    assert.deepEqual(gunzipSync(await readFile(join(dir, `${name}.gz`))), original)
  }
})

test('already-compressed formats are left alone', async () => {
  const dir = await bundle()
  await precompress(dir)
  assert.equal(existsSync(join(dir, 'assets', 'font.woff2.br')), false)
  assert.equal(existsSync(join(dir, 'assets', 'font.woff2.gz')), false)
})

test('a variant that would be larger than the original is not written', async () => {
  const dir = await bundle()
  await precompress(dir)
  assert.equal(existsSync(join(dir, 'assets', 'tiny.js.br')), false)
  assert.equal(existsSync(join(dir, 'assets', 'tiny.js.gz')), false)
})
