import Testing
import Observation
import Synchronization

/// Polls `condition` until true or the timeout elapses.
///
/// **Not for positive waits on `@Observable` state — use `awaitObservation`.** A wall-clock
/// deadline loses to a starved scheduler: on a saturated CI simulator a correct run can take
/// longer than any fixed ceiling to deliver the mutation, the poll gives up, and the `#expect`
/// after it fails. That is exactly how `liveChangePropagatesToObservableSurface` and the two
/// document-provider tests flaked on a runner where every test took 49–106 s.
///
/// **Prefer the fakes' `AsyncGate` waits** (`progress.waitForStarted(bookId:)`,
/// `progress.waitForPositionUpdate(…)`, `engine.waitUntilPaused()`, etc.) where a fake can signal.
/// This poll remains for exactly two shapes:
/// - **Bounded negative checks** (`timeout: .milliseconds(300)`) — "give the wrong thing a chance
///   to happen, then assert it didn't". Absence has no event to await, so these cannot be causal.
/// - **Conditions nothing observes** — the engine fake's actor-isolated state
///   (`await engine.lastGainDb`) and the progress fake's recorded-call arrays, which are neither
///   `@Observable` nor exposed through a predicate gate.
@MainActor
func awaitUntil(
    timeout: Duration = .seconds(30),
    pollInterval: Duration = .milliseconds(20),
    _ condition: () async -> Bool
) async {
    let deadline = ContinuousClock.now + timeout
    while ContinuousClock.now < deadline {
        if await condition() { return }
        try? await Task.sleep(for: pollInterval)
    }
}

/// Causally await a coordinator `@Observable` condition — suspends until the tracked state
/// actually mutates, resuming the instant it does, with no hop ceiling and no wall-clock poll.
///
/// This replaced a fixed cooperative-hop poll that lost a real CI race. The buffering→playing
/// promotion (and, via the `prepare` path, the load-failure `.error` transition) is driven by
/// work that is NOT purely main-actor: `FlowBridge`'s `for await … in engine.events` drives
/// `AsyncStream.Iterator.next()` — a `nonisolated async` call that parks on the generic executor
/// (SE-0338) — and the prepare/cover fakes are likewise `nonisolated async`. On a CPU-starved CI
/// runner (parallel simulator clones) those off-main resumptions land on the wall clock *after* a
/// fixed hop budget has drained, so the poll exited with the condition still false and the
/// assertion failed (no hang, ~normal duration). Observation waits for the real mutation however
/// long it takes — it can't lose that race.
///
/// **The watchdog.** Pure observation never gives up: a mutation that never comes (a regression,
/// or a condition over state observation doesn't track) would park the test forever, and a local
/// `xcodebuild test` sets no execution-time allowance to rescue it. So each wait is also woken by
/// a deadline, after which the helper records a named issue and returns. The deadline is a
/// backstop, not a race: a green run resumes on the mutation and never reaches it, so it is set
/// far above anything a starved runner has shown (whole tests at 49–106 s) — 240 s, under CI's
/// 300 s maximum execution-time allowance. Only a genuinely missing mutation pays it.
///
/// The condition must read observation-tracked state (a coordinator's stored properties, or
/// properties derived from them); an untracked read never fires `onChange` and can only end at
/// the watchdog.
///
/// (Prefer the fakes' `AsyncGate` waits where a fake can signal causally; use this for a
/// coordinator `@Observable` transition no fake owns, e.g. the engine-event-driven phase promotion.)
@MainActor
func awaitObservation(
    timeout: Duration = .seconds(240),
    sourceLocation: SourceLocation = #_sourceLocation,
    _ condition: @escaping @MainActor () -> Bool
) async {
    let deadline = ContinuousClock.now + timeout
    while !condition() {
        guard ContinuousClock.now < deadline else {
            Issue.record(
                "awaitObservation: condition still false after \(timeout) — the mutation never happened",
                sourceLocation: sourceLocation
            )
            return
        }
        let wake = OneShotWake()
        let watchdog = Task { @MainActor in
            try? await Task.sleep(until: deadline)
            wake.fire()
        }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            wake.arm(continuation)
            withObservationTracking { _ = condition() } onChange: { wake.fire() }
        }
        watchdog.cancel()
    }
}

/// Resumes one continuation exactly once, whichever of its two wakers — the observation
/// `onChange` (which fires on the mutating thread) or the watchdog — gets there first; the loser
/// is a no-op. A fire that lands before `arm` resumes the continuation the moment it is armed.
private final class OneShotWake: Sendable {
    private enum State {
        case idle
        case armed(CheckedContinuation<Void, Never>)
        case fired
    }

    private let state = Mutex<State>(.idle)

    func arm(_ continuation: CheckedContinuation<Void, Never>) {
        let firedAlready = state.withLock { state -> Bool in
            if case .fired = state { return true }
            state = .armed(continuation)
            return false
        }
        if firedAlready { continuation.resume() }
    }

    func fire() {
        let armed = state.withLock { state -> CheckedContinuation<Void, Never>? in
            defer { state = .fired }
            if case .armed(let continuation) = state { return continuation }
            return nil
        }
        armed?.resume()
    }
}
