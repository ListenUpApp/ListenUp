// The arithmetic behind "is this run over, and did it pass?", lifted out of run-kotest.mjs so it
// can be exercised without launching a browser.
//
// This harness is the thing that decides whether a lane lied about what it ran. A harness that
// mis-decides in silence is the worst failure available here — and this logic has already produced
// two of them in two days: once calling a suite finished three seconds in, once never calling it
// finished at all. Neither was reachable from the lane it was verified against. Hence: pure
// functions, held by tests, in a file with no side effects.

/**
 * Tests that reported `testStarted` and have not yet reported a result — i.e. still running.
 *
 * `ignored` is deliberately not part of this. Kotest's TeamCity stream emits `testIgnored` for a
 * test that never ran, with no preceding `testStarted`, so an ignored test was never counted in
 * `started` and there is nothing to subtract. Subtracting it drives the value negative on any lane
 * that disables a spec — which is not zero, so a settle condition written as `=== 0` can never be
 * satisfied.
 */
export const inFlight = ({ started, finished }) => started - finished

/**
 * Whether the run has settled: at least one test reported, nothing still running, and the message
 * stream quiet for [idleMs].
 *
 * Quiescence alone is not enough. "Nothing has been reported lately" is satisfied trivially by a
 * single slow test that emits `testStarted` and then spends twenty seconds booting a Koin graph in
 * silence — so the in-flight clause is what stops a partial run being reported as a whole one.
 */
export const isSettled = (counts, { now, lastMessageAt, idleMs }) =>
  counts.started > 0 && inFlight(counts) === 0 && now - lastMessageAt > idleMs

/**
 * Every reason this run is not a pass, as human-readable sentences — empty when it is one.
 *
 * Each clause is independent on purpose: a run can both time out and have failures, and hiding the
 * second behind the first is how a fix gets declared complete while something else is still broken.
 */
export const problemsFor = ({ counts, timedOut, minTests, ceilingMs }) => {
  const problems = []
  if (timedOut) problems.push(`the run did not settle within ${ceilingMs / 1000}s`)
  if (counts.started < minTests) {
    problems.push(`discovered ${counts.started} tests, expected at least ${minTests}`)
  }
  const dangling = inFlight(counts)
  if (dangling > 0) problems.push(`${dangling} test(s) started but never reported a result`)
  if (counts.failed > 0) problems.push(`${counts.failed} test(s) failed`)
  return problems
}
