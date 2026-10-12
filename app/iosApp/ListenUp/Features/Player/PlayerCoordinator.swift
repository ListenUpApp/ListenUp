import SwiftUI
import AVFoundation
@preconcurrency import MediaPlayer
@preconcurrency import Shared

/// Swift Export exposes the nested `PlaybackManager.ChapterInfo` type only through an
/// `internal` typealias, so app code names it via its underlying public wrapper class.
typealias PlaybackManagerChapterInfo =
    _ExportedKotlinPackages_com_calypsan_listenup_client_playback_PlaybackManager_ChapterInfo

// The pure decision helpers (InterruptionPolicy, RouteChangePolicy, ScenePhasePolicy,
// LoadGeneration, ChapterMath) live in PlayerPolicies.swift.

/// The book the engine holds: what it reports progress against, and the lock screen shows.
struct EngineBook: Equatable, Sendable {
    let bookId: String
    /// Total book duration in milliseconds.
    let durationMs: Int64
}

/// The iOS engine adapter (player spec S1). The shared `PlayerSession` decides what is loaded and
/// what the user asked for; this executes `NativePlayerEngine`'s ordered instructions on AVAudioEngine
/// and reports what the engine actually does back as facts. It owns everything AVFoundation-shaped:
/// the audio session, interruptions and route changes, the gain stage, the lock screen and remote
/// commands, and position persistence (pause, seek, scene phase). Player UI reads `PlayerObserver`.
@Observable
@MainActor
final class PlayerCoordinator: RemoteCommandHandler {

    // MARK: - Engine state

    /// What the engine is doing. Every change is reported to the shared player.
    private(set) var enginePhase: EnginePhaseFact = .idle {
        didSet {
            facts.phase(enginePhase)
            updateBandwidthSignal()
            saveRefinedMeasurement()
        }
    }

    /// The book the engine holds, or nil when nothing is loaded (or its load failed).
    private(set) var engineBook: EngineBook?

    var isPlaying: Bool { enginePhase == .playing }
    var isBuffering: Bool { enginePhase == .buffering }

    /// Playback intent is "advancing": the audio is playing OR buffering toward playing. Remote
    /// commands and the interruption policy treat the two as one.
    var isPlaybackActive: Bool { isPlaying || isBuffering }

    /// The book the engine holds — `nil` when nothing is loaded.
    var currentBookId: String? { engineBook?.bookId }

    // MARK: - Lock-screen metadata (set once per load)

    private(set) var bookTitle: String = ""
    private(set) var authorName: String = ""
    private(set) var coverPath: String?
    private(set) var playbackSpeed: Float = 1.0
    /// Volume-boost gain inputs for the loaded book. The boost API, the gain math and the
    /// measurement persistence live in `PlayerCoordinator+Gain.swift`; only the stored state
    /// stays here, because Swift extensions cannot declare stored properties.
    var gain = GainState()
    /// Bridged Kotlin chapters — read only by `ChapterMath`'s lock-screen window (in `remoteSeek` and
    /// `updateNowPlaying()`). Never fed to a view (rule 8).
    private(set) var chapters: [Chapter] = []
    /// The native chapter rows, for the memoized chapter index (the lock screen's re-stamp at a boundary).
    private(set) var chapterRows: [ChapterRowModel] = []
    /// Chapter identity tracks the COARSE position (see `refreshChapterIndex()`).
    private(set) var chapterIndex: Int = 0

    // MARK: - Skip intervals (observed from Settings, for the remote skip commands)

    private(set) var skipForwardSec: Int = 30
    private(set) var skipBackwardSec: Int = 10

    // MARK: - Position (PositionTracker)

    var bookDurationMs: Int64 { engineBook?.durationMs ?? 0 }

    /// Fine, per-frame position — the smooth scrubber thumb's source while a book is loaded.
    var bookPositionMs: Int64 { positionTracker.positionMs }

    /// Coarse, ~1×/sec position.
    var displayPositionMs: Int64 { positionTracker.displayPositionMs }

    /// The tracker's per-frame position while the engine holds a book, `nil` otherwise — what
    /// `PlayerObserver` reads for the smooth thumb before falling back to the shared 4 Hz progress.
    var livePositionMs: Int64? { engineBook == nil ? nil : positionTracker.positionMs }

    // MARK: - Components

    /// Internal, not private: the gain stage lives in its own file (`PlayerCoordinator+Gain.swift`),
    /// as does the now-playing bridge below.
    let engine: PlaybackEngine
    private let positionTracker = PositionTracker()
    /// Internal, not private: the Now-Playing bridge lives in `PlayerCoordinator+NowPlaying.swift`.
    let system: SystemIntegration

    // MARK: - Seam (native protocols — see EngineSeam.swift, PlaybackSeam.swift)

    /// Held for the coordinator's lifetime: production's `KotlinEngineInstructions` owns the FlowBridge that
    /// collects `NativePlayerEngine.commands`, and nothing else references it. Dropped, it cancels on deinit
    /// and every command from the shared player goes unread.
    private let instructions: EngineInstructionSource
    private let facts: EngineFactReporting
    /// Internal, not private — see `engine` above.
    let progress: PlaybackProgressReporting
    /// Reactive source of the user's skip-interval settings, for the lock-screen skip commands.
    /// Optional so fake-injected tests skip it.
    private let skipIntervals: SkipIntervalProviding?
    /// Source of the audio-session interruption/route-change notifications this coordinator observes.
    /// Tests inject a private center so a post from one test can't reach another test's coordinator.
    private let notificationCenter: NotificationCenter
    private let bridge = FlowBridge()
    /// The one task draining the shared player's instructions, in order (spec R2).
    private var drainTask: Task<Void, Never>?

    private var lastReportedPositionMs: Int64 = 0
    private var lastSyncedChapterIndex: Int = -1
    /// The in-flight engine load for the current `.load`, cancelled when another supersedes it.
    private var loadTask: Task<Void, Never>?
    /// Bumped on every `.load` and `.stop`. An in-flight load compares the generation it captured
    /// against this after each `await` and abandons if superseded.
    private var loadGeneration = 0
    /// True from a `.load` until its `engine.load` resolves. While set, engine events are dropped —
    /// they belong to the outgoing book or the muted preroll — and transport instructions are
    /// recorded (`playWhenLoaded`, `pendingSeekMs`) and applied once the book has loaded.
    private var isEngineLoading = false
    /// Whether the book being loaded starts playing when its load resolves (a `.pause` while loading clears it).
    private var playWhenLoaded = true
    /// A `.seek` that arrived while the book was loading, applied once it has loaded.
    private var pendingSeekMs: Int64?
    /// The last `.load`, kept so a play after an engine failure reloads the book (never stranded):
    /// the shared player shows a dismissed failure as Paused and sends a plain `.play` from there.
    private var lastLoad: (bookId: String, prepared: PreparedPlayback)?
    /// Where a failed book resumes when it is played again.
    private var failedAtMs: Int64?
    /// True only while playback is paused *because of* an audio-session interruption. Gates
    /// `.ended + .shouldResume`: a user's deliberate pause is never overridden.
    private var pausedByInterruption = false
    private static let positionReportIntervalMs: Int64 = 5000
    /// Feeds the shared "playback preempts downloads" signal. Optional for unit tests.
    private let bandwidthCoordinator: PlaybackBandwidthCoordinator?
    /// Whether the loaded book streams (has a non-local file) — gates the yield signal.
    private var isCurrentBookStreaming = false

    // MARK: - Init

    init(
        instructions: EngineInstructionSource,
        facts: EngineFactReporting,
        progress: PlaybackProgressReporting,
        engine: PlaybackEngine,
        system: SystemIntegration = SystemIntegration(),
        skipIntervals: SkipIntervalProviding? = nil,
        bandwidthCoordinator: PlaybackBandwidthCoordinator? = nil,
        notificationCenter: NotificationCenter = .default
    ) {
        self.instructions = instructions
        self.facts = facts
        self.progress = progress
        self.engine = engine
        self.system = system
        self.skipIntervals = skipIntervals
        self.bandwidthCoordinator = bandwidthCoordinator
        self.notificationCenter = notificationCenter
        system.attach(handler: self)
        system.updateSkipIntervals(forwardSeconds: skipForwardSec, backwardSeconds: skipBackwardSec)
        bridge.bind(engine.events) { [weak self] in self?.handleEngineEvent($0) }
        observeSkipIntervals()
        observeInterruptions()
        observeRouteChanges()
        let stream = instructions.instructions
        drainTask = Task { [weak self] in
            for await instruction in stream {
                await self?.execute(instruction)
            }
        }
    }

    isolated deinit {
        drainTask?.cancel()
    }

    /// Wires the Kotlin adapters; the now-playing bridge publishes through the engine's
    /// `MPNowPlayingSession` so the system reads state off the attached player.
    convenience init(deps: Dependencies) {
        let engine = AudioEngine()
        let kotlinEngine = deps.nativePlayerEngine
        self.init(
            instructions: KotlinEngineInstructions(engine: kotlinEngine),
            facts: KotlinEngineFacts(engine: kotlinEngine),
            progress: KotlinProgressReporting(reporter: deps.playbackProgressReporter),
            engine: engine,
            system: SystemIntegration(session: engine.nowPlayingSession),
            skipIntervals: KotlinSkipIntervalProviding(preferences: deps.playbackPreferences),
            bandwidthCoordinator: deps.playbackBandwidthCoordinator
        )
    }

    /// Track the user's skip-interval settings, so the lock-screen skip commands and their glyphs
    /// stay live with the setting.
    private func observeSkipIntervals() {
        guard let skipIntervals else { return }
        bridge.bind(skipIntervals.forwardSeconds) { [weak self] seconds in
            guard let self, seconds != skipForwardSec else { return }
            skipForwardSec = seconds
            system.updateSkipIntervals(forwardSeconds: seconds, backwardSeconds: skipBackwardSec)
        }
        bridge.bind(skipIntervals.backwardSeconds) { [weak self] seconds in
            guard let self, seconds != skipBackwardSec else { return }
            skipBackwardSec = seconds
            system.updateSkipIntervals(forwardSeconds: skipForwardSec, backwardSeconds: seconds)
        }
    }

    private func observeInterruptions() {
        let name = AVAudioSession.interruptionNotification
        bridge.bind(notificationCenter.notifications(named: name)) { [weak self] note in
            guard let self else { return }
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
            let shouldResume: Bool = {
                guard let opts = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt else { return false }
                return AVAudioSession.InterruptionOptions(rawValue: opts).contains(.shouldResume)
            }()
            let action = InterruptionPolicy.action(type: type, shouldResume: shouldResume)
            Task { @MainActor in await self.applyInterruption(action) }
        }
    }

    private func observeRouteChanges() {
        let name = AVAudioSession.routeChangeNotification
        bridge.bind(notificationCenter.notifications(named: name)) { [weak self] note in
            guard let self else { return }
            // The status slot names an AirPlay device (spec §4.7); every route change may move it.
            let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
            facts.route(outputs.first { $0.portType == .airPlay }?.portName)
            guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  let reason = AVAudioSession.RouteChangeReason(rawValue: raw),
                  RouteChangePolicy.shouldPause(reason: reason) else { return }
            Task { @MainActor in await self.applyInterruption(.pause) }
        }
    }

    private func applyInterruption(_ action: InterruptionPolicy.Action) async {
        guard let loaded = engineBook else { return }
        switch action {
        case .pause:
            // .playing OR .buffering: both mean audio intent is "advancing".
            if isPlaybackActive {
                pausedByInterruption = true
                await engine.pause()
                enginePhase = .paused
                reportPaused(loaded.bookId)
                updateNowPlaying()
            }
        case .resume:
            guard pausedByInterruption else { return }
            pausedByInterruption = false
            if !isPlaying {
                await engine.activateSession()
                await engine.play()
                enginePhase = .playing
                progress.onPlaybackStarted(bookId: loaded.bookId, positionMs: bookPositionMs,
                    speed: playbackSpeed, durationMs: bookDurationMs)
                updateNowPlaying()
            }
        case .none:
            break
        }
    }

    // MARK: - Instructions

    /// Execute one instruction from the shared player. Awaited by the drain task, so instructions
    /// reach the engine in the order they were issued; a `.load` only starts its load task and
    /// returns, so a later `.load` supersedes it and a `.stop` cancels it.
    func execute(_ instruction: EngineInstruction) async {
        switch instruction {
        case let .load(bookId, prepared): load(bookId: bookId, prepared: prepared)
        case .play: await play()
        case .pause: await pause()
        case .seek(let positionMs): await seek(toMs: positionMs)
        case let .setRate(rate, isReset): await setRate(rate, isReset: isReset)
        case let .setBoost(db, isReset): if isReset { resetBoost(defaultDb: db) } else { setBoost(db) }
        case .setVolume(let volume): await engine.setVolume(volume)
        case .stop: await stopEngine()
        }
    }

    /// Load a prepared book and play it from `prepared.resumePositionMs`. Safe while another book
    /// plays or loads: the in-flight load is superseded, the outgoing book's place is saved, and its
    /// metadata is replaced synchronously, so a switch never leaks the old book's audio or chapters.
    private func load(bookId: String, prepared: PreparedPlayback) {
        pausedByInterruption = false
        loadTask?.cancel()
        loadGeneration &+= 1
        let generation = loadGeneration
        // Gate engine events for the whole switch→load window (see `isEngineLoading`).
        isEngineLoading = true
        playWhenLoaded = true
        pendingSeekMs = nil
        failedAtMs = nil
        lastLoad = (bookId, prepared)
        // Save the outgoing book's place — a switch must not lose it. A paused book already did.
        let hadLoadedBook = engineBook != nil
        if let outgoing = engineBook, isPlaybackActive { reportPaused(outgoing.bookId) }

        resetMetadataForSwitch()
        adopt(prepared)
        let segments = AudioSegment.resolve(prepared.timeline.files)
        guard !segments.isEmpty else {
            engineBook = nil
            if hadLoadedBook { Task { await engine.pause() } }
            fail(.couldNotStart(detail: "no audio"), resumeAtMs: prepared.resumePositionMs)
            return
        }
        // A book streams if any file has no local path — set BEFORE the phase change so the
        // `enginePhase` didSet's bandwidth signal sees the right streaming-ness for this book.
        isCurrentBookStreaming = prepared.timeline.files.contains { $0.localPath == nil }
        // Honest buffering: the book with its real duration the instant we have it, before the
        // (possibly slow, streaming) load. The first real "playing" status promotes it.
        engineBook = EngineBook(bookId: bookId, durationMs: prepared.timeline.totalDurationMs)
        enginePhase = .buffering
        lastSyncedChapterIndex = chapterIndex
        updateNowPlaying()

        loadTask = Task {
            // Silence the outgoing book immediately, before the (possibly slow) load.
            if hadLoadedBook { await engine.pause() }
            await startLoaded(bookId: bookId, prepared: prepared, segments: segments, generation: generation)
        }
    }

    /// The prepared book's metadata, adopted once per load.
    private func adopt(_ prepared: PreparedPlayback) {
        bookTitle = prepared.bookTitle
        authorName = prepared.bookAuthor
        coverPath = prepared.coverPath
        chapters = prepared.chapters
        // The single boundary crossing per book load: bridge each Kotlin `Chapter` once (rule 8).
        chapterRows = prepared.chapters.map {
            ChapterRowModel(id: $0.id, title: $0.title, startMs: $0.startTime, durationMs: $0.duration)
        }
        playbackSpeed = prepared.resumeSpeed
        // Seed the tracker with the resume position (paused) so the lock screen and the smooth thumb
        // show the right place before the engine's first real sample lands.
        positionTracker.update(positionMs: prepared.resumePositionMs, rate: 0)
        refreshChapterIndex()
        lastReportedPositionMs = prepared.resumePositionMs
    }

    private func startLoaded(
        bookId: String, prepared: PreparedPlayback, segments: [AudioSegment], generation: Int
    ) async {
        // `load` awaits readiness and seeks to the resume position before returning.
        let loaded = await engine.load(segments: segments, startPositionMs: prepared.resumePositionMs)
        guard !isSuperseded(generation) else { return }
        // The load is done — lift the event gate so the engine's real events drive the phase.
        isEngineLoading = false
        guard loaded else {
            fail(.couldNotStart(detail: "engine load failed"), resumeAtMs: prepared.resumePositionMs)
            return
        }

        await applyGain(from: prepared)
        await engine.setRate(prepared.resumeSpeed)
        guard !isSuperseded(generation) else { return }
        if let pendingSeekMs {
            self.pendingSeekMs = nil
            await seek(toMs: pendingSeekMs)
        }
        guard playWhenLoaded else {
            enginePhase = .paused
            updateNowPlaying()
            return
        }
        await engine.play()
        guard !isSuperseded(generation) else { return }

        // Report started only after the engine has actually been told to play, and only for the
        // book that survived the switch — so a superseded load never reports a phantom start.
        lastReportedPositionMs = bookPositionMs
        progress.onPlaybackStarted(bookId: bookId, positionMs: bookPositionMs,
            speed: prepared.resumeSpeed, durationMs: prepared.timeline.totalDurationMs)
    }

    /// The engine could not load or play the book. The failure is reported typed; the book is no
    /// longer held (nothing to save against), and a later `.play` reloads it from where it was.
    private func fail(_ failure: EngineFailure, resumeAtMs: Int64) {
        isEngineLoading = false
        failedAtMs = resumeAtMs
        engineBook = nil
        facts.failure(failure)
        enginePhase = .paused
        updateNowPlaying()
    }

    private func play() async {
        pausedByInterruption = false
        if isEngineLoading { playWhenLoaded = true; return }
        guard let loaded = engineBook else {
            // A failed book played again: reload it where it was (never stranded).
            if let lastLoad, let failedAtMs {
                load(bookId: lastLoad.bookId, prepared: lastLoad.prepared.withResumePosition(failedAtMs))
            }
            return
        }
        guard !isPlaybackActive else { return }
        await engine.play()
        enginePhase = .playing
        progress.onPlaybackStarted(bookId: loaded.bookId, positionMs: bookPositionMs,
            speed: playbackSpeed, durationMs: bookDurationMs)
        updateNowPlaying()
    }

    private func pause() async {
        pausedByInterruption = false
        if isEngineLoading { playWhenLoaded = false; return }
        guard let loaded = engineBook, isPlaybackActive else { return }
        await engine.pause()
        enginePhase = .paused
        reportPaused(loaded.bookId)
        updateNowPlaying()
    }

    /// Seek to a whole-book position in milliseconds.
    private func seek(toMs positionMs: Int64) async {
        if isEngineLoading { pendingSeekMs = positionMs; return }
        guard let loaded = engineBook else {
            if failedAtMs != nil { failedAtMs = positionMs }
            return
        }
        // Capture the pre-seek position BEFORE issuing the seek so the span split gets a correct edge.
        let beforeMs = bookPositionMs
        // Split the listening span (finalize pre-seek at `beforeMs`, reopen at `positionMs`) so the
        // jumped-over range isn't fabricated as listened content. This also persists the new position.
        progress.onSeek(bookId: loaded.bookId, beforeMs: beforeMs, afterMs: positionMs, speed: playbackSpeed)
        lastReportedPositionMs = positionMs
        facts.position(ms: positionMs)
        updateNowPlaying()
        // Awaited, so a play the shared player sends after the seek reaches the engine after it (R-4).
        await engine.seek(toMs: positionMs)
    }

    private func setRate(_ speed: Float, isReset: Bool) async {
        playbackSpeed = speed
        if let id = engineBook?.bookId {
            if isReset {
                progress.onSpeedReset(bookId: id, positionMs: bookPositionMs, defaultSpeed: speed)
            } else {
                progress.onSpeedChanged(bookId: id, positionMs: bookPositionMs, newSpeed: speed)
            }
        }
        updateNowPlaying()
        await engine.setRate(speed)
    }

    /// Reset the metadata and position state for a fresh load. Keeps `playbackSpeed` and the
    /// volume boost (re-derived from the prepared book) to avoid a flash back to defaults.
    private func resetMetadataForSwitch() {
        bookTitle = ""
        authorName = ""
        coverPath = nil
        chapters = []
        chapterRows = []
        gain.clearMeasurements()
        positionTracker.reset()
        refreshChapterIndex()
        lastReportedPositionMs = 0
        lastSyncedChapterIndex = -1
    }

    /// Recompute the memoized chapter index from `chapterRows` and the tracker's coarse position.
    private func refreshChapterIndex() {
        chapterIndex = ChapterMath.index(forPositionMs: displayPositionMs, in: chapterRows) ?? 0
    }

    /// Persist the current position immediately. Called on pause, seek, and when the app
    /// backgrounds/terminates — the periodic 5 s tick is not enough to guarantee the place survives a kill.
    func saveCurrentPosition() async {
        // Key on the book the engine holds: saving during a load would zero an untouched resume point.
        guard let id = engineBook?.bookId else { return }
        await progress.savePositionNow(bookId: id, positionMs: bookPositionMs)
    }

    /// Unload the engine — sign-out's teardown, awaited so the audio session is deactivated and the
    /// engine unloaded before it returns — and tell the shared player. Not terminal: the coordinator
    /// is an app-lifetime singleton and plays the next book.
    func stop() async {
        await stopEngine()
        facts.unloaded()
    }

    /// The `.stop` instruction's body. The shared player already knows (it issued the stop), so this
    /// reports nothing back: an `unloaded` fact here could land after the next book's load and drop it.
    private func stopEngine() async {
        pausedByInterruption = false
        // Supersede any in-flight load: `Task.cancel()` alone does not interrupt its awaits, so bump
        // the epoch and it bails at its next `isSuperseded` check instead of resurrecting playback.
        loadTask?.cancel()
        loadGeneration &+= 1
        isEngineLoading = false
        lastLoad = nil
        failedAtMs = nil
        engineBook = nil
        // Return to idle so the didSet releases the download-yield signal.
        enginePhase = .idle
        resetMetadataForSwitch()
        updateNowPlaying()
        await engine.deactivateSession()
        await engine.unload()
    }

    // MARK: - RemoteCommandHandler (Decision 12: engine-level, the session sees the resulting facts)

    func remoteTogglePlayPause() {
        if isPlaybackActive { Task { await pause() } } else { Task { await play() } }
    }
    func remotePlay() { if !isPlaybackActive { Task { await play() } } }
    func remotePause() { if isPlaybackActive { Task { await pause() } } }
    func remoteSkipForward() {
        let target = min(bookPositionMs + Int64(skipForwardSec) * 1000, bookDurationMs)
        Task { await seek(toMs: target) }
    }
    func remoteSkipBackward() {
        let target = max(bookPositionMs - Int64(skipBackwardSec) * 1000, 0)
        Task { await seek(toMs: target) }
    }
    func remoteSetRate(_ rate: Float) {
        facts.rate(rate)
        Task { await setRate(rate, isReset: false) }
    }
    /// The lock-screen scrubber spans the CURRENT CHAPTER (see `updateNowPlaying`), so the position
    /// it reports is window-relative. Translate it back to a book position here — the single
    /// translation point — and everything downstream keeps speaking book coordinates.
    func remoteSeek(toWindowPositionMs positionMs: Int64) {
        let window = ChapterMath.window(
            forPositionMs: bookPositionMs,
            in: chapters,
            bookDurationMs: bookDurationMs
        )
        let target = ChapterMath.bookPosition(forWindowPositionMs: positionMs, in: window)
        Task { await seek(toMs: target) }
    }

    /// Whether a newer `.load` or `.stop` has superseded the load that captured `generation`.
    private func isSuperseded(_ generation: Int) -> Bool {
        LoadGeneration.isSuperseded(taskGeneration: generation, current: loadGeneration)
    }

    // MARK: - Engine events

    private func handleEngineEvent(_ event: AudioEngineEvent) {
        // Drop any engine event that arrives while a book is loading: it belongs to the outgoing
        // book or the muted preroll. Critically this covers `.failed` — a late failure from book A
        // must not abort book B's start. B's own load failure surfaces via `engine.load` returning false.
        if isEngineLoading { return }
        // Nothing is loaded — a late event belongs to the book we just unloaded. Drop it.
        guard let loaded = engineBook else { return }
        switch event {
        case .position(let ms, let rate):
            // A positive-rate sample IS "audio is advancing" — promote a lingering `.buffering` whose
            // `.ready` KVO transition was swallowed by the loading gate.
            if rate > 0, isBuffering {
                enginePhase = .playing
                updateNowPlaying()
            }
            positionTracker.update(positionMs: ms, rate: rate)
            refreshChapterIndex()
            facts.position(ms: ms)
            reportPositionIfNeeded(ms)
            if chapterIndex != lastSyncedChapterIndex {
                lastSyncedChapterIndex = chapterIndex
                // Re-stamp the lock screen at the boundary: its pushed duration/elapsed are scoped to the
                // CURRENT chapter, and the info center would otherwise extrapolate past the old window.
                updateNowPlaying()
            }
        case .statusChanged(let status):
            applyEngineStatus(status)
        case .ended:
            progress.onBookFinished(bookId: loaded.bookId, positionMs: bookPositionMs, durationMs: bookDurationMs)
            enginePhase = .ended
            updateNowPlaying()
        case .failed(let failure):
            fail(failure, resumeAtMs: bookPositionMs)
        }
    }

    /// Collapse the engine's interleaved ready/buffering stream into phase transitions.
    /// `.ready` promotes a buffering book to playing; `.buffering` demotes a playing book.
    /// A paused player ignores status churn.
    private func applyEngineStatus(_ status: AudioEngineStatus) {
        switch status {
        case .buffering:
            if isPlaying {
                enginePhase = .buffering
                updateNowPlaying()
            }
        case .ready:
            if isBuffering {
                enginePhase = .playing
                updateNowPlaying()
            }
        case .idle:
            break
        }
    }

    private func reportPositionIfNeeded(_ positionMs: Int64) {
        guard let id = engineBook?.bookId else { return }
        if abs(positionMs - lastReportedPositionMs) >= Self.positionReportIntervalMs {
            lastReportedPositionMs = positionMs
            progress.onPositionUpdate(bookId: id, positionMs: positionMs, speed: playbackSpeed)
        }
    }

    // The shared tracker decides whether a pause or an end finishes the book: it needs both numbers.
    private func reportPaused(_ bookId: String) {
        progress.onPlaybackPaused(
            bookId: bookId, positionMs: bookPositionMs, speed: playbackSpeed, durationMs: bookDurationMs)
    }

    // MARK: - Playback-preempts-downloads signal

    /// Tell the shared coordinator whether a STREAMING book is currently buffering, so background
    /// downloads yield only during the stalls that matter (a local book never asks them to).
    private func updateBandwidthSignal() {
        bandwidthCoordinator?.setStreamingBuffering(active: isBuffering && isCurrentBookStreaming)
    }
}
