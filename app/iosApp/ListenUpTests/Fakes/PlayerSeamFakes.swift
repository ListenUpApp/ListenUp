import Foundation
@testable import ListenUp
@preconcurrency import Shared

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
    private(set) var lastGainDb: Float?
    private var measuredGainDb: Float?
    private(set) var didRelease = false
    private(set) var didUnload = false
    /// Mirrors `AudioEngine.isReleased`: `release()` is terminal on the real engine, so the fake
    /// must refuse a `load` after it too — otherwise a coordinator-level "play after stop" test
    /// passes against the fake while the real engine is dead.
    private var isReleased = false
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
    private(set) var volumeCount = 0
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

    func load(segments: [ListenUp.AudioSegment], startPositionMs: Int64) async -> Bool {
        if isReleased { commandLog.append("load-after-release"); return false }
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
    func seek(toMs positionMs: Int64) async { lastSeekMs = positionMs; commandLog.append("seek"); gate.fire("seek") }
    func setRate(_ newRate: Float) async { lastRate = newRate; gate.fire("setRate") }
    func setVolume(_ volume: Float) async {
        lastVolume = volume; volumeCount += 1; commandLog.append("volume")
        gate.fire("setVolume"); gate.fire("volume-\(volumeCount)")
    }
    func setGainDb(_ db: Float) async { lastGainDb = db; gate.fire("setGainDb") }
    func currentMeasuredGainDb() async -> Float? {
        gate.fire("measuredGain")
        if shouldBlockMeasuredGain { await withCheckedContinuation { measuredGainBlocker = $0 } }
        return measuredGainDb
    }
    /// Stand-in for the R128 meter's reading, so a test can drive the measured-gain path
    /// without a live audio tap.
    func setMeasuredGainDb(_ db: Float?) { measuredGainDb = db }

    /// When true, `currentMeasuredGainDb` signals entry then suspends until
    /// `releaseMeasuredGain()` — lets a test hold the measurement read open and switch books
    /// underneath the coordinator's in-flight save.
    private var shouldBlockMeasuredGain = false
    private var measuredGainBlocker: CheckedContinuation<Void, Never>?
    func setBlockMeasuredGain(_ block: Bool) { shouldBlockMeasuredGain = block }
    func releaseMeasuredGain() { measuredGainBlocker?.resume(); measuredGainBlocker = nil }

    /// Suspend until the coordinator has entered the measured-gain read.
    func waitForMeasuredGainRead() async { await gate.wait(forKey: "measuredGain") }
    func deactivateSession() async {
        didDeactivateSession = true; teardownOrder.append("deactivate"); gate.fire("deactivate")
    }
    func activateSession() async {
        didActivateSession = true; commandLog.append("activate"); gate.fire("activate")
    }
    func release() async {
        didRelease = true; isReleased = true; teardownOrder.append("release"); gate.fire("release")
    }
    /// Non-terminal teardown: unloads the current item and its observers but leaves the
    /// engine reusable — mirrors `AudioEngine.unload()`.
    func unload() async { didUnload = true; teardownOrder.append("unload"); gate.fire("unload") }

    /// Suspend until `unload()` has executed.
    func waitUntilUnloaded() async { await gate.wait(forKey: "unload") }

    /// Suspend until `pause()` has executed. Returns immediately if it already has.
    func waitUntilPaused() async { await gate.wait(forKey: "pause") }

    /// Suspend until `seek(toMs:)` has executed. Returns immediately if it already has.
    func waitUntilSeeked() async { await gate.wait(forKey: "seek") }

    /// Suspend until `play()` has executed for the `count`-th time. Keyed, because a
    /// predicate closure can't read this actor's state (see AsyncGate lines 46–59).
    func waitForPlayCount(_ count: Int) async { await gate.wait(forKey: "play-\(count)") }

    /// Suspend until `setVolume` has executed for the `count`-th time. Keyed, like `waitForPlayCount`.
    func waitForVolumeCount(_ count: Int) async { await gate.wait(forKey: "volume-\(count)") }

    /// Suspend until `load` has been entered (before it returns / while blocked).
    func waitForLoadEntered() async { await gate.wait(forKey: "load") }
}

// MARK: - Task 2 seam fakes

@MainActor
final class FakeProgressReporting: PlaybackProgressReporting {
    private(set) var startedCalls: [(String, Int64, Float)] = []
    private(set) var pausedCalls: [(String, Int64, Float)] = []
    private(set) var positionUpdates: [(String, Int64, Float)] = []
    private(set) var seeks: [(String, Int64, Int64, Float)] = []
    private(set) var speedChanges: [(String, Int64, Float)] = []
    private(set) var speedResets: [(String, Int64, Float)] = []
    private(set) var boostChanges: [(String, Int64, Float)] = []
    private(set) var boostResets: [(String, Int64, Float)] = []
    private(set) var measuredGains: [(String, Int64, Float)] = []
    private(set) var finished: [(bookId: String, positionMs: Int64, durationMs: Int64)] = []
    /// The `durationMs` each start carried, in call order (kept apart so `startedCalls` stays a triple).
    private(set) var startedDurations: [Int64] = []
    /// The `durationMs` each pause carried, in call order (kept apart so `pausedCalls` stays a triple).
    private(set) var pausedDurations: [Int64] = []
    private(set) var savedNow: [(String, Int64)] = []

    /// Fires on each recorded call. The fake is `@MainActor`, so the coordinator's `signal()`
    /// callbacks and the tests' `wait`s share the one main-actor isolation domain the
    /// lock-free `AsyncGate` requires — its `wait`s inherit `#isolation == MainActor`, making
    /// the check-then-append atomic against `signal()`. Without the isolation the wait methods,
    /// being `nonisolated async`, would hop to the generic executor and race `signal()`.
    private let gate = AsyncGate()

    func onPlaybackStarted(bookId: String, positionMs: Int64, speed: Float, durationMs: Int64) {
        startedDurations.append(durationMs)
        startedCalls.append((bookId, positionMs, speed)); gate.signal()
    }
    func onPlaybackPaused(bookId: String, positionMs: Int64, speed: Float, durationMs: Int64) {
        pausedDurations.append(durationMs)
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
    func onSpeedReset(bookId: String, positionMs: Int64, defaultSpeed: Float) {
        speedResets.append((bookId, positionMs, defaultSpeed)); gate.signal()
    }
    func onVolumeBoostChanged(bookId: String, positionMs: Int64, newBoostDb: Float) {
        boostChanges.append((bookId, positionMs, newBoostDb)); gate.signal()
    }
    func onBoostReset(bookId: String, positionMs: Int64, defaultBoostDb: Float) {
        boostResets.append((bookId, positionMs, defaultBoostDb)); gate.signal()
    }
    func onMeasuredGain(bookId: String, positionMs: Int64, gainDb: Float) {
        measuredGains.append((bookId, positionMs, gainDb)); gate.signal()
    }
    func onBookFinished(bookId: String, positionMs: Int64, durationMs: Int64) {
        finished.append((bookId, positionMs, durationMs)); gate.signal()
    }
    /// Saves the caller's cancellation reached. The real bridged Kotlin suspend checks for cancellation
    /// on entry and cancels its coroutine when the calling task is cancelled, so each of these is a
    /// place the real reporter would have lost.
    private(set) var cancelledSaves: [(String, Int64)] = []
    /// While true, `savePositionNow` holds each save open until `releaseHeldSave()` — a slow write a
    /// test can cancel the caller in the middle of.
    var holdsSaves = false
    private var isSaveReleased = false
    private var isSaveEntered = false

    func savePositionNow(bookId: String, positionMs: Int64) async {
        if holdsSaves {
            isSaveEntered = true; gate.signal()
            await gate.wait { [weak self] in self?.isSaveReleased ?? true }
        }
        guard !Task.isCancelled else {
            cancelledSaves.append((bookId, positionMs)); gate.signal()
            return
        }
        savedNow.append((bookId, positionMs)); gate.signal()
    }

    /// Suspend until a held save has been entered (see `holdsSaves`).
    func waitForSaveEntered() async {
        await gate.wait { [weak self] in self?.isSaveEntered ?? true }
    }

    /// Let a held save finish.
    func releaseHeldSave() {
        isSaveReleased = true; gate.signal()
    }

    /// Suspend until playback has been reported started for `bookId`.
    ///
    /// This is the canonical "the coordinator has issued the start" anchor: the coordinator
    /// enters `.buffering`, calls `engine.play()`, then reports `onPlaybackStarted` — so once
    /// this returns, `engineBook` is set, but `isPlaying` is
    /// **not yet true**. The engine's first "playing" status event (auto-emitted by
    /// `FakePlaybackEngine.play()`) promotes `.buffering → .playing` shortly after; a test that
    /// needs `isPlaying == true` should `await awaitObservation { coordinator.isPlaying }` after this.
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

// MARK: - Engine seam fakes (the shared player's side of the coordinator)

/// The shared player's instructions, driven by the test: each `send` reaches the coordinator before it returns,
/// as the production bridge hands each collected command over.
@MainActor
final class FakeEngineInstructions: EngineInstructionSource {
    private var receive: (@MainActor (EngineInstruction) -> Void)?
    /// Instructions sent before the coordinator started listening, handed over when it does.
    private var undelivered: [EngineInstruction] = []

    func deliver(to receive: @escaping @MainActor (EngineInstruction) -> Void) {
        self.receive = receive
        undelivered.forEach(receive)
        undelivered.removeAll()
    }

    func send(_ instruction: EngineInstruction) {
        if let receive { receive(instruction) } else { undelivered.append(instruction) }
    }
}

/// Records every fact the coordinator reports to the shared player.
@MainActor
final class FakeEngineFacts: EngineFactReporting {
    private(set) var phases: [EnginePhaseFact] = []
    private(set) var positions: [Int64] = []
    private(set) var rates: [Float] = []
    private(set) var routes: [String?] = []
    private(set) var failures: [EngineFailure] = []
    private(set) var unloadedCount = 0
    /// The load generation each book fact carried, in report order (the route is not about a book).
    private(set) var generations: [EngineLoadGeneration] = []
    private(set) var failureGenerations: [EngineLoadGeneration] = []
    func phase(_ phase: EnginePhaseFact, generation: EngineLoadGeneration) {
        phases.append(phase); generations.append(generation)
    }
    func position(ms: Int64, generation: EngineLoadGeneration) {
        positions.append(ms); generations.append(generation)
    }
    func rate(_ rate: Float, generation: EngineLoadGeneration) { rates.append(rate); generations.append(generation) }
    func boost(_ db: Float, generation: EngineLoadGeneration) { generations.append(generation) }
    func route(_ deviceName: String?) { routes.append(deviceName) }
    func failure(_ failure: EngineFailure, generation: EngineLoadGeneration) {
        failures.append(failure); failureGenerations.append(generation); generations.append(generation)
    }
    func unloaded(generation: EngineLoadGeneration) { unloadedCount += 1; generations.append(generation) }
    /// Each remote play the coordinator could not serve itself, by the generation it named.
    private(set) var playRequests: [EngineLoadGeneration] = []
    func playRequested(generation: EngineLoadGeneration) { playRequests.append(generation) }
}

extension ListenUp.PreparedPlayback {
    /// A one-file, chapterless prepared book: the base every coordinator test varies.
    static func fixture(
        resumePositionMs: Int64 = 0,
        durationMs: Int64 = 60000,
        resumeSpeed: Float = 1.0,
        resumeBoostDb: Float = 0,
        measuredGainDb: Float? = nil,
        normalizationGainDb: Float? = nil,
        coverPath: String? = nil,
        chapters: [Chapter] = [],
        localPath: String? = "/a.m4a",
        streamingUrl: String = ""
    ) -> ListenUp.PreparedPlayback {
        ListenUp.PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: coverPath,
            resumeSpeed: resumeSpeed, resumeBoostDb: resumeBoostDb, measuredGainDb: measuredGainDb,
            normalizationGainDb: normalizationGainDb, resumePositionMs: resumePositionMs, chapters: chapters,
            timeline: PreparedTimeline(totalDurationMs: durationMs, files: [
                PreparedFile(localPath: localPath, streamingUrl: streamingUrl, durationMs: durationMs, startOffsetMs: 0)
            ])
        )
    }
}

/// A coordinator over fakes, plus the fakes a test drives it through and observes it by.
@MainActor
struct PlayerHarness {
    let coordinator: PlayerCoordinator
    let engine: FakePlaybackEngine
    let progress: FakeProgressReporting
    let instructions: FakeEngineInstructions
    let facts: FakeEngineFacts

    init(
        skipIntervals: SkipIntervalProviding? = nil,
        notificationCenter: NotificationCenter = NotificationCenter()
    ) {
        engine = FakePlaybackEngine()
        progress = FakeProgressReporting()
        instructions = FakeEngineInstructions()
        facts = FakeEngineFacts()
        coordinator = PlayerCoordinator(
            instructions: instructions, facts: facts, progress: progress, engine: engine,
            skipIntervals: skipIntervals, notificationCenter: notificationCenter)
    }

    /// Execute one instruction now, as the drain task would, and wait for its synchronous part.
    func run(_ instruction: EngineInstruction) async { await coordinator.execute(instruction) }

    /// Load `prepared` as `bookId` and wait until the coordinator has reported it started.
    func loadAndStart(
        _ bookId: String = "book1",
        _ prepared: ListenUp.PreparedPlayback = .fixture(),
        generation: EngineLoadGeneration = 0
    ) async {
        await run(.load(bookId: bookId, prepared: prepared, generation: generation))
        await progress.waitForStarted(bookId: bookId)
    }
}
