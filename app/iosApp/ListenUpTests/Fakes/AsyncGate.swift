import Foundation

/// Deterministic "await until a condition holds" without wall-clock polling.
///
/// The old player tests waited on async work with a real-time `awaitUntil` poll and a
/// generous (~30 s) ceiling. On a CPU-starved CI runner with many parallel simulator
/// clones saturating the shared `@MainActor`, the scheduler could take longer than the
/// ceiling to drain a chain of suspension points — so a *correct* run failed on the clock.
///
/// `AsyncGate` replaces the clock with causality: a fake records a mutation, then calls
/// `signal()`; a test `await wait(until:)`s the exact state transition it cares about and
/// resumes the instant that transition happens — never sooner, and with no timeout to lose
/// a race against.
///
/// **Isolation — why the `isolation:` parameter is load-bearing.** The gate has no internal
/// locking: register (`wait`), signal (`signal`/`fire`), and predicate evaluation must all
/// happen in one isolation domain — the same domain that mutates the observed state. The
/// fakes satisfy this: `FakePlaybackEngine` confines its gate to its actor; the
/// `@MainActor`-touched reporting/sleep fakes and the boot recorder confine theirs to the
/// main actor. But a *plain* `nonisolated async` method does **not** run in its caller's
/// domain — it hops onto the generic cooperative executor. That silently broke the invariant:
/// `wait` ran off the main actor while `fire`/`signal` (synchronous) ran on it, racing the
/// `waiters` array from two threads. A `fire()` landing between `wait`'s predicate check and
/// its waiter append was a lost wakeup — the continuation was never resumed and the test hung
/// forever (surfacing in CI as an un-attributable 45-minute job stall, not a named failure).
/// The `isolation: isolated (any Actor)? = #isolation` parameter pins each `wait` to its
/// caller's actor, so registration and signalling genuinely share one single-threaded domain
/// and the check-then-append is atomic against `fire`/`signal`. `@unchecked Sendable` so the
/// gate can be stored in the `Sendable` player fakes; soundness now rests on that honoured
/// single-domain discipline, not on an assumption the compiler was quietly violating.
final class AsyncGate: @unchecked Sendable {
    private var waiters: [(predicate: () -> Bool, resume: () -> Void)] = []
    /// Keys that have fired at least once, for the keyed `fire`/`wait(forKey:)` API used by
    /// actor-confined fakes (whose isolated state a non-isolated predicate closure can't read).
    private var firedKeys: Set<String> = []

    // MARK: - Predicate API (for non-isolated, `@unchecked Sendable` fakes)

    /// Suspend until `predicate` holds. Returns immediately if it already does, so a test
    /// that awaits an already-completed transition never blocks.
    ///
    /// Runs in the caller's isolation domain (`isolation` defaults to the caller via
    /// `#isolation`) so the predicate check and the waiter append are atomic against a
    /// concurrent `signal()`/`fire()` on that same domain — see the type doc for why that
    /// is what makes the gate race-free.
    func wait(
        until predicate: @escaping () -> Bool,
        isolation: isolated (any Actor)? = #isolation
    ) async {
        if predicate() { return }
        await withCheckedContinuation { continuation in
            waiters.append((predicate, { continuation.resume() }))
        }
    }

    /// Re-evaluate every waiter; resume and drop those whose predicate now holds. Call after
    /// each mutation of the observed state, in that state's isolation domain.
    func signal() {
        resumeSatisfied()
    }

    // MARK: - Keyed API (for actor-confined fakes)

    /// Record that `key` has occurred and resume any waiters now satisfied. The keyed form
    /// keeps the "has it happened?" state inside the gate, so the predicate never has to read
    /// the actor's isolated properties — which a non-isolated closure cannot do.
    func fire(_ key: String) {
        firedKeys.insert(key)
        resumeSatisfied()
    }

    /// Suspend until `key` has fired. Returns immediately if it already has. Inherits the
    /// caller's isolation domain and threads it into the underlying `wait(until:)`.
    func wait(
        forKey key: String,
        isolation: isolated (any Actor)? = #isolation
    ) async {
        await wait(until: { [weak self] in self?.firedKeys.contains(key) ?? false }, isolation: isolation)
    }

    private func resumeSatisfied() {
        var stillWaiting: [(predicate: () -> Bool, resume: () -> Void)] = []
        for waiter in waiters {
            if waiter.predicate() {
                waiter.resume()
            } else {
                stillWaiting.append(waiter)
            }
        }
        waiters = stillWaiting
    }
}
