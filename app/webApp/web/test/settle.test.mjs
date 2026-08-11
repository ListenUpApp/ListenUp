// The harness's own tests. These run before every browser lane (`pnpm test`), because a broken
// verdict function is indistinguishable from a broken suite until you read 180 seconds of log.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { inFlight, isSettled, problemsFor } from './settle.mjs'

const QUIET = { now: 10_000, lastMessageAt: 0, idleMs: 3_000 }

// The numbers below are the two real lanes as measured on 2026-08-11: the same 147 specs compiled
// once, with three of them gated on a live server. `webKotest` runs 144 and ignores 3;
// `webAuthKotest` boots a server and runs all 147.
const SERVER_FREE = { started: 144, finished: 144, ignored: 3, failed: 0 }
const SERVER_BACKED = { started: 147, finished: 147, ignored: 0, failed: 0 }

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
