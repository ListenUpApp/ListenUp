// The harness's own tests. These run before every browser lane (`pnpm test`), because a broken
// verdict function is indistinguishable from a broken suite until you read 180 seconds of log.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { inFlight, isSettled, problemsFor } from './settle.mjs'

const QUIET = { now: 10_000, lastMessageAt: 0, idleMs: 3_000 }

// The shapes below are the two real lanes as they stood on 2026-08-11 — the same 147 specs
// compiled once, three of them gated on a live server; `webKotest` ran 144 and ignored 3,
// `webAuthKotest` booted a server and ran all 147. The suite has grown since (820 / 826 as of
// 2026-09-08 — see `kotest-baseline.json`, which is what the runner actually compares against);
// these fixtures only need the SHAPE of a settled lane, so the smaller numbers keep the
// arithmetic legible.
const SERVER_FREE = { started: 144, finished: 144, ignored: 3, failed: 0 }
const SERVER_BACKED = { started: 147, finished: 147, ignored: 0, failed: 0 }

const baseline = JSON.parse(
  readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), 'kotest-baseline.json'), 'utf8'),
)

test('a finished run has nothing in flight even when specs were ignored', () => {
  assert.equal(inFlight(SERVER_FREE), 0)
  assert.equal(inFlight(SERVER_BACKED), 0)
})

test('ignored specs do not keep the run from settling', () => {
  // The regression: `ignored` was subtracted from a `started` that never contained it, so this
  // went negative, `=== 0` never held, and the lane ran to the ceiling and failed with every
  // test passing. Reachable only on a lane that ignores something — which the auth lane does not.
  assert.equal(isSettled(SERVER_FREE, QUIET), true)
  assert.equal(isSettled(SERVER_BACKED, QUIET), true)
})

test('a run is not settled while a test is still running', () => {
  const midRun = { started: 144, finished: 143, ignored: 3, failed: 0 }
  assert.equal(inFlight(midRun), 1)
  assert.equal(isSettled(midRun, QUIET), false)
})

test('a quiet stream is not settled before anything has started', () => {
  assert.equal(isSettled({ started: 0, finished: 0, ignored: 0, failed: 0 }, QUIET), false)
})

test('a still-chattering stream is not settled', () => {
  const noisy = { ...QUIET, lastMessageAt: QUIET.now - 100 }
  assert.equal(isSettled(SERVER_FREE, noisy), false)
})

test('a clean run at its floor reports no problems', () => {
  const problems = problemsFor({
    counts: SERVER_FREE,
    timedOut: false,
    minTests: 144,
    ceilingMs: 180_000,
  })
  assert.deepEqual(problems, [])
})

test('a lane that quietly drops a spec fails its floor', () => {
  const problems = problemsFor({
    counts: { started: 143, finished: 143, ignored: 4, failed: 0 },
    timedOut: false,
    minTests: 144,
    ceilingMs: 180_000,
  })
  assert.deepEqual(problems, ['discovered 143 tests, expected at least 144'])
})

test('a test that started and never reported is a problem', () => {
  const problems = problemsFor({
    counts: { started: 144, finished: 140, ignored: 3, failed: 0 },
    timedOut: false,
    minTests: 144,
    ceilingMs: 180_000,
  })
  assert.deepEqual(problems, ['4 test(s) started but never reported a result'])
})

test('every independent problem is reported, not just the first', () => {
  const problems = problemsFor({
    counts: { started: 10, finished: 8, ignored: 0, failed: 2 },
    timedOut: true,
    minTests: 144,
    ceilingMs: 180_000,
  })
  assert.deepEqual(problems, [
    'the run did not settle within 180s',
    'discovered 10 tests, expected at least 144',
    '2 test(s) started but never reported a result',
    '2 test(s) failed',
  ])
})

test('the committed baseline names both lanes', () => {
  assert.equal(Number.isInteger(baseline.serverFree), true)
  assert.equal(Number.isInteger(baseline.serverBacked), true)
  assert.equal(baseline.serverFree > 0, true)
  assert.equal(baseline.serverBacked > 0, true)
})

test('the server-backed lane runs exactly six tests the server-free lane skips', () => {
  // The six `.config(enabled = serverBooted)` sites are the whole documented difference between
  // the lanes. A seventh must land with a matching change here, in the same commit:
  //   RpcTransportTest.kt:41
  //   AuthArcTest.kt:34
  //   LibrarySyncTest.kt:24
  //   ProductionWebSocketConfigTest.kt:44
  //   playback/HlsPlaybackTest.kt:97
  //   playback/HlsPlaybackTest.kt:148
  assert.equal(baseline.serverBacked - baseline.serverFree, 6)
})

test('the baseline is not a stale fraction of the suite', () => {
  // A floor on the floor. The runner's constant once sat at 211 while the suite grew to 820 —
  // a lane that quietly dropped three quarters of its specs would still have reported PASS.
  // This is the guard against that exact regression recurring, not a number to keep bumping:
  // it only needs to be well below the real count and far above anything a broken run could
  // plausibly discover.
  assert.equal(baseline.serverFree > 700, true)
})
