// The worker's VFS choice, tested directly.
//
// ⛔ This exists because `insecure-boot.mjs` cannot prove it. Sabotage showed the app boots, shows
// its banner and reaches a usable screen even with the fallback removed entirely — a database that
// fails to open raises nothing the page can observe. So the browser gate covers "the app runs and
// says what it lost", and the decision underneath it is covered here, where it is observable.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { openDatabase } from '../../worker/open-database.js'

class FakeOpfsDb {
  constructor(fileName) { this.kind = 'opfs'; this.fileName = fileName }
}
class FakeDb {
  constructor(fileName) { this.kind = 'memory'; this.fileName = fileName }
}

test('a browser with OPFS gets the persistent database', () => {
  const sqlite3 = { oo1: { OpfsDb: FakeOpfsDb, DB: FakeDb } }

  const db = openDatabase('listenup.db', sqlite3)

  assert.equal(db.kind, 'opfs')
  assert.equal(db.fileName, 'listenup.db', 'the persistent database must be the named file')
})

test('a browser without OPFS falls back to memory rather than throwing', () => {
  // How an insecure origin actually presents: the VFS never installs, so sqlite3 exposes no
  // OpfsDb at all. This used to be a dead web client.
  const sqlite3 = { oo1: { DB: FakeDb } }

  const db = openDatabase('listenup.db', sqlite3)

  assert.equal(db.kind, 'memory')
  assert.equal(db.fileName, ':memory:', 'the fallback must be in-memory, not a named file')
})
