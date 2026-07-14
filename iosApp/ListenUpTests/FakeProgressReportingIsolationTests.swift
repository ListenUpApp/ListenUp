import Testing
@testable import ListenUp

/// Regression coverage for `FakeProgressReporting`'s isolation contract — the residual
/// `PlayerSwitchPathTests` flake (a different test hung to a ~15 s timeout each CI run under
/// parallel simulator clones).
///
/// `FakeProgressReporting` drives an `AsyncGate`, which is lock-free and therefore requires its
/// `wait`s and `signal()`s to share one isolation domain (see `AsyncGate`). The fake used to be a
/// plain non-isolated `@unchecked Sendable` class, so its `waitForStarted`/… — being
/// `nonisolated async` — hopped onto the generic executor while the coordinator's `onPlaybackStarted`
/// signalled on the main actor. A `signal()` landing between the wait's predicate check and its
/// waiter append was a lost wakeup: the awaiter blocked forever and the test hung. It only bit under
/// CI's contended parallel clones, where the generic-executor task is starved past the signal.
///
/// The fix makes the fake (and its `PlaybackProgressReporting` protocol, matching the sibling
/// `SleepTiming`/`SkipIntervalProviding` seams) `@MainActor`, so wait and signal share the main
/// actor and the check-then-append is atomic against the signal. This test pins that: a signal
/// issued *after* the wait has begun, from the wait's own (main-actor) domain, always resumes it.
/// Under the pre-fix off-actor fake this races and can hang; under the isolated fake it is
/// deterministic on the single-threaded domain.
@MainActor
@Suite("FakeProgressReporting isolation")
struct FakeProgressReportingIsolationTests {
    @Test func waitForStartedResumesWhenSignalFiresAfterWaitBegins() async {
        let progress = FakeProgressReporting()
        let waiting = Task { @MainActor in await progress.waitForStarted(bookId: "book1") }
        // Hand the main actor to the child so its wait registers, then signal from this same
        // domain. A lost wakeup here would hang `waiting.value` — the exact PlayerSwitchPath flake.
        await Task.yield()
        progress.onPlaybackStarted(bookId: "book1", positionMs: 0, speed: 1.0)
        await waiting.value
    }

    @Test func waitForStartedReturnsImmediatelyWhenAlreadyStarted() async {
        let progress = FakeProgressReporting()
        progress.onPlaybackStarted(bookId: "book1", positionMs: 0, speed: 1.0)
        // Already recorded, no signal to come — the fast path must return without blocking.
        await progress.waitForStarted(bookId: "book1")
    }
}
