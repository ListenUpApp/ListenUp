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
/// a race against. A hang here means the awaited work genuinely never occurred, which is the
/// honest failure we want (Swift Testing surfaces it as a stuck test, not a flaky pass).
///
/// **Isolation:** the gate has no internal locking. Register, signal, and evaluate the
/// predicate must all happen in one isolation domain — the same domain that mutates the
/// observed state. The player fakes satisfy this: `FakePlaybackEngine` confines its gate to
/// its actor; the `@MainActor`-touched reporting/sleep fakes confine theirs to the main actor.
/// `@unchecked Sendable` so it can be stored in the `Sendable` player fakes. There is no
/// internal synchronization — soundness rests entirely on the single-isolation-domain
/// discipline documented above, exactly as the fakes' own `@unchecked Sendable` does.
final class AsyncGate: @unchecked Sendable {
    private var waiters: [(predicate: () -> Bool, resume: () -> Void)] = []
    /// Keys that have fired at least once, for the keyed `fire`/`wait(forKey:)` API used by
    /// actor-confined fakes (whose isolated state a non-isolated predicate closure can't read).
    private var firedKeys: Set<String> = []

    // MARK: - Predicate API (for non-isolated, `@unchecked Sendable` fakes)

    /// Suspend until `predicate` holds. Returns immediately if it already does, so a test
    /// that awaits an already-completed transition never blocks.
    func wait(until predicate: @escaping () -> Bool) async {
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

    /// Suspend until `key` has fired. Returns immediately if it already has.
    func wait(forKey key: String) async {
        await wait { [weak self] in self?.firedKeys.contains(key) ?? false }
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
