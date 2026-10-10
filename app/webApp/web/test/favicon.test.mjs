// The tab's icon. A browser that finds no <link rel="icon"> falls back to /favicon.ico, which the
// server doesn't have, so the tab shows a blank page glyph. Every icon the page names must be a
// file Vite copies from public/ into the bundle's root.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const web = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const indexHtml = readFileSync(join(web, 'index.html'), 'utf8')

function iconLinks() {
  return [...indexHtml.matchAll(/<link\s[^>]*rel="(icon|apple-touch-icon)"[^>]*>/g)].map((m) => m[0])
}

function hrefOf(link) {
  return /href="([^"]+)"/.exec(link)[1]
}

test('the page names an SVG favicon and a touch icon', () => {
  const links = iconLinks()
  assert.equal(links.some((l) => l.includes('rel="icon"') && l.includes('type="image/svg+xml"')), true)
  assert.equal(links.some((l) => l.includes('rel="apple-touch-icon"')), true)
})

test('every icon the page names ships from public/', () => {
  const links = iconLinks()
  assert.equal(links.length > 0, true)
  for (const link of links) {
    const href = hrefOf(link)
    assert.equal(href.startsWith('/'), true, `${href} must be root-absolute, every route serves this page`)
    assert.equal(existsSync(join(web, 'public', href.slice(1))), true, `${href} is not in public/`)
  }
})

test('the SVG favicon stays visible on a dark tab strip', () => {
  const svg = readFileSync(join(web, 'public', 'favicon.svg'), 'utf8')
  assert.match(svg, /@media\s*\(prefers-color-scheme:\s*dark\)/)
})
