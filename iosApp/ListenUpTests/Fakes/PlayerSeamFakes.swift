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

    /// Fires whenever a recorded command lands, so a test can `await` the exact
    /// transition (e.g. play, pause) deterministically instead of polling the clock.
    private let gate = AsyncGate()

    init() {
        var c: AsyncStream<AudioEngineEvent>.Continuation!
        events = AsyncStream { c = $0 }
        continuation = c
    }

    /// Push an event into the coordinator's bound stream from a test.
    nonisolated func emit(_ event: AudioEngineEvent) { continuation.yield(event) }

    func load(segments: [AudioSegment], startPositionMs: Int64) async {}
    func play() async { didPlay = true; gate.fire("play") }
    func pause() async { didPause = true; gate.fire("pause") }
    func seek(toMs positionMs: Int64) async { lastSeekMs = positionMs; gate.fire("seek") }
    func setRate(_ newRate: Float) async { lastRate = newRate; gate.fire("setRate") }
    func setVolume(_ volume: Float) async { lastVolume = volume; gate.fire("setVolume") }
    func deactivateSession() async {
        didDeactivateSession = true; teardownOrder.append("deactivate"); gate.fire("deactivate")
    }
    func release() async { didRelease = true; teardownOrder.append("release"); gate.fire("release") }

    /// Suspend until `pause()` has executed. Returns immediately if it already has.
    func waitUntilPaused() async { await gate.wait(forKey: "pause") }
}

// MARK: - Task 2 seam fakes

final class FakeProgressReporting: PlaybackProgressReporting, @unchecked Sendable {
    private(set) var startedCalls: [(String, Int64, Float)] = []
    private(set) var pausedCalls: [(String, Int64, Float)] = []
    private(set) var positionUpdates: [(String, Int64, Float)] = []
    private(set) var speedChanges: [(String, Int64, Float)] = []
    private(set) var finished: [(String, Int64)] = []
    private(set) var savedNow: [(String, Int64)] = []

    /// Fires on each recorded call. The coordinator drives this fake on the main actor and
    /// tests await on the main actor — one isolation domain, so the gate needs no locking.
    private let gate = AsyncGate()

    func onPlaybackStarted(bookId: String, positionMs: Int64, speed: Float) {
        startedCalls.append((bookId, positionMs, speed)); gate.signal()
    }
    func onPlaybackPaused(bookId: String, positionMs: Int64, speed: Float) {
        pausedCalls.append((bookId, positionMs, speed)); gate.signal()
    }
    func onPositionUpdate(bookId: String, positionMs: Int64, speed: Float) {
        positionUpdates.append((bookId, positionMs, speed)); gate.signal()
    }
    func onSpeedChanged(bookId: String, positionMs: Int64, newSpeed: Float) {
        speedChanges.append((bookId, positionMs, newSpeed)); gate.signal()
    }
    func onBookFinished(bookId: String, finalPositionMs: Int64) {
        finished.append((bookId, finalPositionMs)); gate.signal()
    }
    func savePositionNow(bookId: String, positionMs: Int64) async {
        savedNow.append((bookId, positionMs)); gate.signal()
    }

    /// Suspend until playback has been reported started for `bookId`.
    ///
    /// This is the canonical "the coordinator has finished starting" anchor: the coordinator
    /// sets `phase = .playing` and *then* calls `onPlaybackStarted`, so once this returns the
    /// observable surface (`isPlaying`, `isVisible`, `phase.playingState`) is already consistent.
    /// Anchoring on `engine.play()` instead would race the coordinator's post-`play` phase write.
    func waitForStarted(bookId: String) async {
        await gate.wait { [weak self] in self?.startedCalls.contains { $0.0 == bookId } ?? false }
    }

    /// Suspend until a position update for `bookId` at `positionMs` has been recorded.
    func waitForPositionUpdate(bookId: String, positionMs: Int64) async {
        await gate.wait { [weak self] in
            self?.positionUpdates.contains { $0.0 == bookId && $0.1 == positionMs } ?? false
        }
    }
}

final class FakeSleepTiming: SleepTiming, @unchecked Sendable {
    let stateStream: AsyncStream<SleepTimingState>
    private let stateContinuation: AsyncStream<SleepTimingState>.Continuation
    let fired: AsyncStream<Void>
    private let firedContinuation: AsyncStream<Void>.Continuation
    private(set) var fadeCompletedCount = 0
    private(set) var chapterChanges: [Int] = []

    /// Fires on fade-completed and chapter-change callbacks (both driven on the main actor),
    /// so tests can await those exact transitions without polling.
    private let gate = AsyncGate()

    init() {
        var sc: AsyncStream<SleepTimingState>.Continuation!
        stateStream = AsyncStream { sc = $0 }; stateContinuation = sc
        var fc: AsyncStream<Void>.Continuation!
        fired = AsyncStream { fc = $0 }; firedContinuation = fc
    }
    func emitFired() { firedContinuation.yield(()) }
    func onFadeCompleted() { fadeCompletedCount += 1; gate.signal() }
    func onChapterChanged(newChapterIndex: Int) { chapterChanges.append(newChapterIndex); gate.signal() }
    func setDurationTimer(minutes: Int) {}
    func setEndOfChapterTimer() {}
    func cancelTimer() {}

    /// Suspend until the fade-completed callback has fired exactly once.
    func waitForFadeCompleted() async {
        await gate.wait { [weak self] in (self?.fadeCompletedCount ?? 0) >= 1 }
    }

    /// Suspend until a chapter change to `index` has been recorded.
    func waitForChapterChange(to index: Int) async {
        await gate.wait { [weak self] in self?.chapterChanges.contains(index) ?? false }
    }
}

final class FakeBookCoverProviding: BookCoverProviding, @unchecked Sendable {
    var blurHash: String?
    func coverBlurHash(bookId: String) async -> String? { blurHash }
}

final class FakePlaybackPreparing: PlaybackPreparing, @unchecked Sendable {
    var result: PreparedPlayback?
    func prepare(bookId: String) async -> PreparedPlayback? { result }
}

final class FakeBookDocumentProviding: BookDocumentProviding, @unchecked Sendable {
    var pdfDocId: String?
    var localPath: String?
    func firstPdfDocId(bookId: String) async -> String? { pdfDocId }
    func ensureLocalPath(bookId: String, docId: String) async -> String? { localPath }
}
