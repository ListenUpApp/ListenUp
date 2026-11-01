import Foundation
import Observation
import os
import Testing
@testable import ListenUp
import ListenupContract
@preconcurrency import Shared

/// The sleep timer ticks once a second; only the surfaces that draw the countdown may redraw for it.
@Suite("A sleep-timer tick reaches only the countdown's readers")
@MainActor
struct PlayerObserverTickTests {
    private static func state(remainingMs: Int64) -> PlayerUiStateLoaded {
        .fixture(sleepTimer: SleepTimerStateActive(
            mode: SleepTimerModeDuration(minutes: 15), remainingMs: remainingMs, totalMs: 900_000, startedAt: 0))
    }

    /// Whether `read` is invalidated by `change`.
    private static func invalidates(reading read: @MainActor () -> Void, by change: @MainActor () -> Void) -> Bool {
        let fired = OSAllocatedUnfairLock(initialState: false)
        withObservationTracking({ read() }, onChange: { fired.withLock { $0 = true } })
        change()
        return fired.withLock { $0 }
    }

    @Test("a tick leaves the mini player's visibility, the chapter buttons and the timer's state alone")
    func tickLeavesTheShellAlone() {
        let observer = PlayerObserver.detached(Self.state(remainingMs: 65_000))
        let tick = { @MainActor in
            observer.replaceDetachedState(Self.state(remainingMs: 64_000), progress: PlayerProgressRow())
        }
        let visibility = Self.invalidates(reading: { _ = observer.isVisible }, by: tick)
        let buttons = Self.invalidates(
            reading: { _ = (observer.canSkipToPreviousChapter, observer.canSkipToNextChapter) }, by: tick)
        let timerState = Self.invalidates(reading: { _ = observer.sleepTimerActive }, by: tick)
        #expect(!visibility)
        #expect(!buttons)
        #expect(!timerState)
    }

    @Test("a tick still invalidates the countdown's readers")
    func tickReachesTheCountdown() {
        let observer = PlayerObserver.detached(Self.state(remainingMs: 65_000))
        let tick = { @MainActor in
            observer.replaceDetachedState(Self.state(remainingMs: 64_000), progress: PlayerProgressRow())
        }
        let countdown = Self.invalidates(reading: { _ = observer.sleepTimerLabel }, by: tick)
        #expect(countdown)
        #expect(observer.sleepTimerLabel == "1:04")
    }

    @Test("thirty ticks redraw a reader of isVisible zero times")
    func thirtyTicks() {
        let observer = PlayerObserver.detached(Self.state(remainingMs: 100_000))
        let counter = OSAllocatedUnfairLock(initialState: 0)
        func arm() {
            withObservationTracking({ _ = observer.isVisible }, onChange: { counter.withLock { $0 += 1 } })
        }
        arm()
        for tick in 1...30 {
            let before = counter.withLock { $0 }
            observer.replaceDetachedState(
                Self.state(remainingMs: 100_000 - Int64(tick) * 1_000), progress: PlayerProgressRow())
            // Re-arm the way a SwiftUI body does after each evaluation.
            if counter.withLock({ $0 }) > before { arm() }
        }
        let redraws = counter.withLock { $0 }
        #expect(redraws == 0, "redraws=\(redraws) of 30 ticks")
    }

    @Test("two consecutive sleep ticks leave the snapshot untouched and move only the observer's remainder")
    func whichFieldsTick() {
        let first = Mirror(reflecting: PlayerSnapshot.from(Self.state(remainingMs: 65_000))).children
        let second = Mirror(reflecting: PlayerSnapshot.from(Self.state(remainingMs: 64_000))).children
        let changed = zip(first, second)
            .filter { String(describing: $0.value) != String(describing: $1.value) }
            .compactMap(\.0.label)
        #expect(changed.isEmpty, "changed=\(changed)")
        #expect(PlayerSnapshot.sleepRemainingMs(of: Self.state(remainingMs: 65_000)) == 65_000)
    }
}
