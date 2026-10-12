import Foundation
import ListenupContract
@preconcurrency import Shared

/// Adapts the Koin-resolved `PlaybackProgressReporter` to `PlaybackProgressReporting`.
/// The reporter fans each signal out to position persistence *and* (on iOS, where a
/// recorder is bound) listening-event recording — so iOS listening history reaches the
/// server. See `PlaybackProgressReporter` in `:app:sharedLogic`.
@MainActor
struct KotlinProgressReporting: PlaybackProgressReporting {
    let reporter: PlaybackProgressReporter
    func onPlaybackStarted(bookId: String, positionMs: Int64, speed: Float, durationMs: Int64) {
        reporter.onPlaybackStarted(
            bookId: BookId(value: bookId), positionMs: positionMs, speed: speed, durationMs: durationMs
        )
    }
    func onPlaybackPaused(bookId: String, positionMs: Int64, speed: Float, durationMs: Int64) {
        reporter.onPlaybackPaused(
            bookId: BookId(value: bookId), positionMs: positionMs, speed: speed, durationMs: durationMs
        )
    }
    func onPositionUpdate(bookId: String, positionMs: Int64, speed: Float) {
        reporter.onPositionUpdate(bookId: BookId(value: bookId), positionMs: positionMs, speed: speed)
    }
    func onSeek(bookId: String, beforeMs: Int64, afterMs: Int64, speed: Float) {
        reporter.onSeek(bookId: BookId(value: bookId), beforeMs: beforeMs, afterMs: afterMs, speed: speed)
    }
    func onSpeedChanged(bookId: String, positionMs: Int64, newSpeed: Float) {
        reporter.onSpeedChanged(bookId: BookId(value: bookId), positionMs: positionMs, newSpeed: newSpeed)
    }
    func onSpeedReset(bookId: String, positionMs: Int64, defaultSpeed: Float) {
        reporter.onSpeedReset(bookId: BookId(value: bookId), positionMs: positionMs, defaultSpeed: defaultSpeed)
    }
    func onVolumeBoostChanged(bookId: String, positionMs: Int64, newBoostDb: Float) {
        reporter.onVolumeBoostChanged(bookId: BookId(value: bookId), positionMs: positionMs, newBoostDb: newBoostDb)
    }
    func onBoostReset(bookId: String, positionMs: Int64, defaultBoostDb: Float) {
        reporter.onBoostReset(bookId: BookId(value: bookId), positionMs: positionMs, defaultBoostDb: defaultBoostDb)
    }
    func onMeasuredGain(bookId: String, positionMs: Int64, gainDb: Float) {
        reporter.onMeasuredGain(bookId: BookId(value: bookId), positionMs: positionMs, gainDb: gainDb)
    }
    func onBookFinished(bookId: String, positionMs: Int64, durationMs: Int64) {
        _ = reporter.onBookFinished(bookId: BookId(value: bookId), positionMs: positionMs, durationMs: durationMs)
    }
    func savePositionNow(bookId: String, positionMs: Int64) async {
        // The Kotlin suspend fun is exposed as `async throws`; it never throws in
        // practice (failures are logged inside the reporter), so the error is dropped.
        try? await reporter.savePositionNow(bookId: BookId(value: bookId), positionMs: positionMs)
    }
}

/// Bridges `PlaybackPreferences`' reactive skip-interval `Flow<Int>`s into native
/// `AsyncStream<Int>`s. The shared store emits the current value on first collect and
/// re-emits on every write (from any surface), so the player tracks the setting live.
@MainActor
final class KotlinSkipIntervalProviding: SkipIntervalProviding {
    private let bridge: FlowBridge
    let forwardSeconds: AsyncStream<Int>
    private let forwardContinuation: AsyncStream<Int>.Continuation
    let backwardSeconds: AsyncStream<Int>
    private let backwardContinuation: AsyncStream<Int>.Continuation

    init(preferences: PlaybackPreferences) {
        self.bridge = FlowBridge()
        var fc: AsyncStream<Int>.Continuation!
        forwardSeconds = AsyncStream { fc = $0 }; forwardContinuation = fc
        var bc: AsyncStream<Int>.Continuation!
        backwardSeconds = AsyncStream { bc = $0 }; backwardContinuation = bc
        // A cold Kotlin `Flow` is exposed over Swift Export via `asAsyncSequence()` (the
        // `observeDocuments` precedent), as a `KotlinFlowSequence<Int32>`. The explicit
        // `Int32` annotation steers `bind` to its `AsyncSequence` overload; convert to `Int`.
        let forward = preferences.observeDefaultSkipForwardSec().asAsyncSequence()
        bridge.bind(forward) { [forwardContinuation] (seconds: Int32) in
            forwardContinuation.yield(Int(seconds))
        }
        let backward = preferences.observeDefaultSkipBackwardSec().asAsyncSequence()
        bridge.bind(backward) { [backwardContinuation] (seconds: Int32) in
            backwardContinuation.yield(Int(seconds))
        }
    }
}
