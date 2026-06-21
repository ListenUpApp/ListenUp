import Foundation
@testable import ListenUp

/// Records every command the coordinator sends the engine. An `actor` so it can
/// satisfy the `Sendable` `PlaybackEngine` protocol exactly as `AudioEngine` does.
actor FakePlaybackEngine: PlaybackEngine {
    private let continuation: AsyncStream<AudioEngineEvent>.Continuation
    nonisolated let events: AsyncStream<AudioEngineEvent>

    private(set) var didPlay = false
    private(set) var didPause = false
    private(set) var lastSeekMs: Int64?
    private(set) var lastRate: Float?
    private(set) var lastVolume: Float?
    private(set) var didRelease = false
    private(set) var didDeactivateSession = false
    /// Records teardown calls in the order they arrive, so a test can assert the
    /// session is deactivated before the engine is released.
    private(set) var teardownOrder: [String] = []

    init() {
        var c: AsyncStream<AudioEngineEvent>.Continuation!
        events = AsyncStream { c = $0 }
        continuation = c
    }

    /// Push an event into the coordinator's bound stream from a test.
    nonisolated func emit(_ event: AudioEngineEvent) { continuation.yield(event) }

    func load(segments: [AudioSegment], startPositionMs: Int64) async {}
    func play() async { didPlay = true }
    func pause() async { didPause = true }
    func seek(toMs positionMs: Int64) async { lastSeekMs = positionMs }
    func setRate(_ newRate: Float) async { lastRate = newRate }
    func setVolume(_ volume: Float) async { lastVolume = volume }
    func deactivateSession() async { didDeactivateSession = true; teardownOrder.append("deactivate") }
    func release() async { didRelease = true; teardownOrder.append("release") }
}

// MARK: - Task 2 seam fakes

final class FakeProgressReporting: PlaybackProgressReporting, @unchecked Sendable {
    private(set) var startedCalls: [(String, Int64, Float)] = []
    private(set) var pausedCalls: [(String, Int64, Float)] = []
    private(set) var positionUpdates: [(String, Int64, Float)] = []
    private(set) var speedChanges: [(String, Int64, Float)] = []
    private(set) var finished: [(String, Int64)] = []
    private(set) var savedNow: [(String, Int64)] = []

    func onPlaybackStarted(bookId: String, positionMs: Int64, speed: Float) { startedCalls.append((bookId, positionMs, speed)) }
    func onPlaybackPaused(bookId: String, positionMs: Int64, speed: Float) { pausedCalls.append((bookId, positionMs, speed)) }
    func onPositionUpdate(bookId: String, positionMs: Int64, speed: Float) { positionUpdates.append((bookId, positionMs, speed)) }
    func onSpeedChanged(bookId: String, positionMs: Int64, newSpeed: Float) { speedChanges.append((bookId, positionMs, newSpeed)) }
    func onBookFinished(bookId: String, finalPositionMs: Int64) { finished.append((bookId, finalPositionMs)) }
    func savePositionNow(bookId: String, positionMs: Int64) async { savedNow.append((bookId, positionMs)) }
}

final class FakeSleepTiming: SleepTiming, @unchecked Sendable {
    let stateStream: AsyncStream<SleepTimingState>
    private let stateContinuation: AsyncStream<SleepTimingState>.Continuation
    let fired: AsyncStream<Void>
    private let firedContinuation: AsyncStream<Void>.Continuation
    private(set) var fadeCompletedCount = 0
    private(set) var chapterChanges: [Int] = []

    init() {
        var sc: AsyncStream<SleepTimingState>.Continuation!
        stateStream = AsyncStream { sc = $0 }; stateContinuation = sc
        var fc: AsyncStream<Void>.Continuation!
        fired = AsyncStream { fc = $0 }; firedContinuation = fc
    }
    func emitFired() { firedContinuation.yield(()) }
    func onFadeCompleted() { fadeCompletedCount += 1 }
    func onChapterChanged(newChapterIndex: Int) { chapterChanges.append(newChapterIndex) }
    func setDurationTimer(minutes: Int) {}
    func setEndOfChapterTimer() {}
    func cancelTimer() {}
}

final class FakeBookCoverProviding: BookCoverProviding, @unchecked Sendable {
    var blurHash: String?
    func coverBlurHash(bookId: String) async -> String? { blurHash }
}

final class FakePlaybackPreparing: PlaybackPreparing, @unchecked Sendable {
    var result: PreparedPlayback?
    func prepare(bookId: String) async -> PreparedPlayback? { result }
}
