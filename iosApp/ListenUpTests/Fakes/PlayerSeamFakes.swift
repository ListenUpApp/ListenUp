import Foundation
@testable import ListenUp

/// Records every command the coordinator sends the engine. An `actor` so it can
/// satisfy the `Sendable` `PlaybackEngine` protocol exactly as `AudioEngine` does.
actor FakePlaybackEngine: PlaybackEngine {
    private let continuation: AsyncStream<AudioEngineEvent>.Continuation
    nonisolated let events: AsyncStream<AudioEngineEvent>

    private(set) var didPlay = false
    private(set) var didPause = false
    private(set) var didLoad = false
    private(set) var lastLoadStartMs: Int64?
    private(set) var lastSeekMs: Int64?
    private(set) var lastRate: Float?
    private(set) var lastVolume: Float?
    private(set) var didRelease = false
    /// When true, `load` reports failure (returns `false`) so tests can exercise the
    /// coordinator's load-failure → `.error` path without a live `AVPlayer`.
    var loadShouldFail = false
    /// When true (default), `play()` emits `.statusChanged(.ready)` to mirror a real player
    /// reaching `timeControlStatus == .playing`, so the coordinator's `.buffering` phase
    /// promotes to `.playing`. Tests that want to *observe* the buffering→playing transition
    /// deterministically set this false and drive the promotion with an explicit `emit(.ready)`.
    var autoReadyOnPlay = true
    private(set) var didDeactivateSession = false
    private(set) var didActivateSession = false
    private(set) var playCount = 0
    /// Every engine command in arrival order, so a test can assert relative
    /// ordering (e.g. `activate` immediately before the resuming `play`).
    private(set) var commandLog: [String] = []
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

    /// Toggle the load-failure simulation (actor-isolated state needs a setter).
    func setLoadShouldFail(_ shouldFail: Bool) { loadShouldFail = shouldFail }

    /// Toggle whether `play()` auto-emits `.ready` (actor-isolated state needs a setter).
    func setAutoReadyOnPlay(_ auto: Bool) { autoReadyOnPlay = auto }

    /// When true, `load` signals entry then suspends until `releaseLoad()` — lets a test hold a
    /// load open and observe the coordinator's intermediate (buffering) state while it's in flight.
    private var shouldBlockLoad = false
    private var loadBlocker: CheckedContinuation<Void, Never>?
    func setBlockLoad(_ block: Bool) { shouldBlockLoad = block }
    func releaseLoad() { loadBlocker?.resume(); loadBlocker = nil }

    func load(segments: [AudioSegment], startPositionMs: Int64) async -> Bool {
        didLoad = true; lastLoadStartMs = startPositionMs; commandLog.append("load")
        gate.fire("load")
        if shouldBlockLoad { await withCheckedContinuation { loadBlocker = $0 } }
        return !loadShouldFail
    }
    func play() async {
        didPlay = true; playCount += 1; commandLog.append("play")
        // Mirror a real player reaching `timeControlStatus == .playing`: emit `.ready` so the
        // coordinator promotes its optimistic `.buffering` phase to `.playing`. Suppressed when
        // a test wants to assert the buffering→playing transition explicitly.
        if autoReadyOnPlay { continuation.yield(.statusChanged(.ready)) }
        gate.fire("play"); gate.fire("play-\(playCount)")
    }
    func pause() async { didPause = true; commandLog.append("pause"); gate.fire("pause") }
    func seek(toMs positionMs: Int64) async { lastSeekMs = positionMs; gate.fire("seek") }
    func setRate(_ newRate: Float) async { lastRate = newRate; gate.fire("setRate") }
    func setVolume(_ volume: Float) async { lastVolume = volume; gate.fire("setVolume") }
    func deactivateSession() async {
        didDeactivateSession = true; teardownOrder.append("deactivate"); gate.fire("deactivate")
    }
    func activateSession() async {
        didActivateSession = true; commandLog.append("activate"); gate.fire("activate")
    }
    func release() async { didRelease = true; teardownOrder.append("release"); gate.fire("release") }

    /// Suspend until `pause()` has executed. Returns immediately if it already has.
    func waitUntilPaused() async { await gate.wait(forKey: "pause") }

    /// Suspend until `play()` has executed for the `count`-th time. Keyed, because a
    /// predicate closure can't read this actor's state (see AsyncGate lines 46–59).
    func waitForPlayCount(_ count: Int) async { await gate.wait(forKey: "play-\(count)") }

    /// Suspend until `load` has been entered (before it returns / while blocked).
    func waitForLoadEntered() async { await gate.wait(forKey: "load") }
}

// MARK: - Task 2 seam fakes

final class FakeProgressReporting: PlaybackProgressReporting, @unchecked Sendable {
    private(set) var startedCalls: [(String, Int64, Float)] = []
    private(set) var pausedCalls: [(String, Int64, Float)] = []
    private(set) var positionUpdates: [(String, Int64, Float)] = []
    private(set) var seeks: [(String, Int64, Int64, Float)] = []
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
    func onSeek(bookId: String, beforeMs: Int64, afterMs: Int64, speed: Float) {
        seeks.append((bookId, beforeMs, afterMs, speed)); gate.signal()
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
    /// This is the canonical "the coordinator has issued the start" anchor: the coordinator
    /// enters `.buffering`, calls `engine.play()`, then reports `onPlaybackStarted` — so once
    /// this returns, `isVisible` and `phase.playingState` are consistent, but `isPlaying` is
    /// **not yet true**. The engine's first "playing" status event (auto-emitted by
    /// `FakePlaybackEngine.play()`) promotes `.buffering → .playing` shortly after; a test that
    /// needs `isPlaying == true` should `await awaitUntil { coordinator.isPlaying }` after this.
    func waitForStarted(bookId: String) async {
        await gate.wait { [weak self] in self?.startedCalls.contains { $0.0 == bookId } ?? false }
    }

    /// Suspend until a position update for `bookId` at `positionMs` has been recorded.
    func waitForPositionUpdate(bookId: String, positionMs: Int64) async {
        await gate.wait { [weak self] in
            self?.positionUpdates.contains { $0.0 == bookId && $0.1 == positionMs } ?? false
        }
    }

    /// Suspend until a seek for `bookId` landing at `afterMs` has been recorded.
    func waitForSeek(bookId: String, afterMs: Int64) async {
        await gate.wait { [weak self] in
            self?.seeks.contains { $0.0 == bookId && $0.2 == afterMs } ?? false
        }
    }
}

@MainActor
final class FakeSleepTiming: SleepTiming {
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
    func onFadeCompleted() { fadeCompletedCount += 1; gate.fire("fadeCompleted") }
    func onChapterChanged(newChapterIndex: Int) {
        chapterChanges.append(newChapterIndex); gate.fire("chapter-\(newChapterIndex)")
    }
    func setDurationTimer(minutes: Int) {}
    func setEndOfChapterTimer() {}
    func cancelTimer() {}

    // The fake is `@MainActor` (its `SleepTiming` protocol is), so a predicate closure
    // reading `self` can't cross into the non-isolated gate. Use the keyed API, which keeps
    // the "did it fire?" state inside the gate — no main-actor `self` in the sent closure.

    /// Suspend until the fade-completed callback has fired exactly once.
    func waitForFadeCompleted() async {
        await gate.wait(forKey: "fadeCompleted")
    }

    /// Suspend until a chapter change to `index` has been recorded.
    func waitForChapterChange(to index: Int) async {
        await gate.wait(forKey: "chapter-\(index)")
    }
}

@MainActor
final class FakeSkipIntervalProviding: SkipIntervalProviding {
    let forwardSeconds: AsyncStream<Int>
    private let forwardContinuation: AsyncStream<Int>.Continuation
    let backwardSeconds: AsyncStream<Int>
    private let backwardContinuation: AsyncStream<Int>.Continuation

    /// Seeds each stream with an initial value, mirroring the real provider's first emission.
    init(initialForward: Int, initialBackward: Int) {
        var fc: AsyncStream<Int>.Continuation!
        forwardSeconds = AsyncStream { fc = $0 }; forwardContinuation = fc
        var bc: AsyncStream<Int>.Continuation!
        backwardSeconds = AsyncStream { bc = $0 }; backwardContinuation = bc
        forwardContinuation.yield(initialForward)
        backwardContinuation.yield(initialBackward)
    }

    /// Push a new interval, simulating a live Settings change.
    func emitForward(_ seconds: Int) { forwardContinuation.yield(seconds) }
    func emitBackward(_ seconds: Int) { backwardContinuation.yield(seconds) }
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
