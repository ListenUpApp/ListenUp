import Testing
@testable import ListenUp

/// Regression coverage for `AsyncGate`'s isolation contract.
///
/// The gate hung `BootSequenceTests` intermittently because `wait` was a plain
/// `nonisolated async` method: it hopped off the caller's actor onto the generic executor
/// while `fire`/`signal` stayed on the caller's actor, racing the `waiters` array. A `fire()`
/// that landed between `wait`'s predicate check and its append was a lost wakeup, and the test
/// hung forever. These tests pin the fixed behaviour — a `fire`/`signal` issued *after* a wait
/// has begun, from the wait's own isolation domain, always resumes it. Under the pre-fix
/// off-actor gate they race and can hang; under the `isolation:`-pinned gate they are
/// deterministic on the single-threaded domain.
@MainActor
@Suite("AsyncGate isolation")
struct AsyncGateTests {
    @Test func resumesWhenKeyFiresAfterWaitBegins() async {
        let gate = AsyncGate()
        let waiting = Task { @MainActor in await gate.wait(forKey: "resumeDownloads") }
        // Hand the main actor to the child so its wait registers, then fire from this same
        // domain. A lost wakeup here would hang `waiting.value` — the exact boot-test failure.
        await Task.yield()
        gate.fire("resumeDownloads")
        await waiting.value
    }

    @Test func resumesWhenPredicateSignalsAfterWaitBegins() async {
        let gate = AsyncGate()
        var ready = false
        let waiting = Task { @MainActor in await gate.wait(until: { ready }) }
        await Task.yield()
        ready = true
        gate.signal()
        await waiting.value
    }

    @Test func returnsImmediatelyWhenKeyAlreadyFired() async {
        let gate = AsyncGate()
        gate.fire("already")
        // No waiter registered, no signal to come — the fast path must return without blocking.
        await gate.wait(forKey: "already")
    }

    @Test func returnsImmediatelyWhenPredicateAlreadyHolds() async {
        let gate = AsyncGate()
        await gate.wait(until: { true })
    }
}
