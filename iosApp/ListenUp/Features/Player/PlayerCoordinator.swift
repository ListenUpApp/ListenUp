import SwiftUI
import AVFoundation
@preconcurrency import Shared

/// Swift Export exposes the nested `PlaybackManager.ChapterInfo` type only through an
/// `internal` typealias, so app code names it via its underlying public wrapper class.
typealias PlaybackManagerChapterInfo =
    _ExportedKotlinPackages_com_calypsan_listenup_client_playback_PlaybackManager_ChapterInfo

// The pure decision helpers (InterruptionPolicy, RouteChangePolicy, ScenePhasePolicy,
// LoadGeneration, ChapterMath) live in PlayerPolicies.swift.

/// The iOS player orchestrator (Option B). Owns `PlayerPhase` and the player-core
/// components, maps the KMP seam, and exposes the flat `@Observable` surface the
/// player UI consumes. The only player file that imports `Shared`.
@Observable
@MainActor
final class PlayerCoordinator: RemoteCommandHandler {

    // MARK: - Runtime phase

    private(set) var phase: PlayerPhase = .idle {
        didSet { updateBandwidthSignal() }
    }

    // MARK: - Preserved UI surface — visibility & playback flags (derived from phase)

    /// A book is loaded (or a load failed) — drives the mini player's visibility. `.error` stays
    /// visible so the inline error+retry is reachable (never stranded); only `.idle` hides it.
    var isVisible: Bool {
        switch phase {
        case .idle: return false
        case .preparing, .playing, .paused, .buffering, .error: return true
        }
    }

    var isPlaying: Bool { phase.isPlaying }

    var isBuffering: Bool {
        if case .buffering = phase { return true }
        return false
    }

    /// True when playback failed to start/continue — drives the inline error+retry surface so the
    /// user is never stranded on a vanished player.
    var isErrored: Bool {
        if case .error = phase { return true }
        return false
    }

    /// The user-facing failure message when `isErrored`, else nil.
    var errorMessage: String? {
        if case .error(let state) = phase { return state.message }
        return nil
    }

    /// Playback intent is "advancing": the audio is playing OR buffering toward playing.
    /// The transport, remote commands, and toggle all treat these two as one — the user's
    /// intent is the same, and the buffering→playing promotion is an internal engine detail.
    var isPlaybackActive: Bool { isPlaying || isBuffering }

    // MARK: - Preserved UI surface — book metadata (set once on load)

    private(set) var bookTitle: String = ""
    private(set) var authorName: String = ""
    /// Comma-joined narrator(s); empty when the book has no narrator (the player
    /// hides the "Narrated by" line in that case).
    private(set) var narratorName: String = ""
    private(set) var coverPath: String?
    private(set) var playbackSpeed: Float = 1.0
    private(set) var chapters: [Chapter] = []

    // MARK: - Preserved UI surface — skip intervals (observed from Settings)

    /// Forward skip interval in seconds. Mirrors the user's synced setting; the
    /// transport's forward button, its glyph, and the lock-screen control all read it.
    private(set) var skipForwardSec: Int = 30
    /// Backward skip interval in seconds. Mirrors the user's synced setting.
    private(set) var skipBackwardSec: Int = 10

    // MARK: - Preserved UI surface — position (derived from PositionTracker)

    var bookDurationMs: Int64 { phase.playingState?.durationMs ?? 0 }

    /// Fine, per-frame position — read only by the scrubber slider (smooth thumb).
    var bookPositionMs: Int64 { positionTracker.positionMs }

    /// Coarse, ~1×/sec position — read by everything that only shows seconds (time
    /// labels, the book-progress bar, the mini-player) so they don't invalidate per frame.
    var displayPositionMs: Int64 { positionTracker.displayPositionMs }
    var displayBookPositionMs: Int64 { positionTracker.displayPositionMs }
    var displayBookProgress: Float {
        bookDurationMs > 0 ? Float(displayPositionMs) / Float(bookDurationMs) : 0
    }

    // MARK: - Preserved UI surface — chapter (computed from chapters + position)

    /// Chapter identity tracks the COARSE position: chapter transitions at 1 s
    /// granularity are imperceptible, and this keeps chapter-derived reads
    /// (title, "Chapter X of Y", duration) off the per-frame invalidation path.
    var chapterIndex: Int { ChapterMath.index(forPositionMs: displayPositionMs, in: chapters) ?? 0 }
    var totalChapters: Int { chapters.count }

    var chapterTitle: String? {
        guard !chapters.isEmpty else { return nil }
        return chapters[chapterIndex].title
    }

    var chapterPositionMs: Int64 {
        guard !chapters.isEmpty else { return 0 }
        return max(0, bookPositionMs - chapters[chapterIndex].startTime)
    }

    var chapterDurationMs: Int64 {
        guard !chapters.isEmpty else { return 0 }
        return chapters[chapterIndex].duration
    }

    func chapterTitleForIndex(_ index: Int) -> String? {
        guard index >= 0, index < chapters.count else { return nil }
        return chapters[index].title
    }

    /// Chapter info for the seeking UI — rebuilt from the Swift-side chapter math.
    var currentChapterInfoForSeeking: PlaybackManagerChapterInfo? {
        guard !chapters.isEmpty else { return nil }
        let index = chapterIndex
        let chapter = chapters[index]
        let endMs = chapter.startTime + chapter.duration
        return PlaybackManagerChapterInfo(
            index: Int32(index),
            title: chapter.title,
            startMs: chapter.startTime,
            endMs: endMs,
            remainingMs: max(0, endMs - bookPositionMs),
            totalChapters: Int32(chapters.count),
            isGenericTitle: false
        )
    }

    // MARK: - Preserved UI surface — sleep timer (observed from KMP)

    private(set) var sleepTimerActive: Bool = false
    private(set) var sleepTimerRemainingMs: Int64 = 0
    private(set) var sleepTimerMode: String = ""
    private(set) var sleepTimerLabel: String = ""

    // MARK: - Components

    private let engine: PlaybackEngine
    private let positionTracker = PositionTracker()
    private let system = SystemIntegration()

    // MARK: - Seam (native protocols — see PlaybackSeam.swift)

    private let preparer: PlaybackPreparing
    private let progress: PlaybackProgressReporting
    private let sleep: SleepTiming
    private let documentProvider: BookDocumentProviding
    /// Reactive source of the user's skip-interval settings. The coordinator observes
    /// its streams so the transport, glyphs, and lock-screen control track the setting
    /// live — a change on the Settings screen lands here mid-session. Optional so the
    /// fake-injected unit tests don't need a settings source.
    private let skipIntervals: SkipIntervalProviding?
    /// Per-step delay of the sleep-timer fade-out. Injected so tests run the fade instantly
    /// (`.zero`) instead of depending on ~3 s of real wall-clock time, which flakes under CI load.
    private let fadeStepDelay: Duration
    private let bridge = FlowBridge()

    private(set) var currentBookId: String?
    /// The id of the first PDF document for the currently-loaded book, or `nil` if none.
    /// Set asynchronously after each book load; drives the "Open PDF" menu item visibility.
    private(set) var firstPdfDocId: String?
    /// Set when a PDF is ready to present; cleared on dismiss. Drives `.fullScreenCover(item:)`.
    var documentToOpen: ReaderDocument?
    private var lastReportedPositionMs: Int64 = 0
    private var lastSyncedChapterIndex: Int = -1
    private var isFading = false
    /// The in-flight prepare-and-start task for the current `play(bookId:)`, cancelled the
    /// instant the user switches books so its post-`await` continuations bail (RC-4).
    private var prepareTask: Task<Void, Never>?
    /// Bumped on every `play(bookId:)`. The current epoch; an in-flight prepare compares the
    /// generation it captured against this after each `await` and abandons if superseded.
    private var loadGeneration = 0
    /// True from the start of a `play(bookId:)` until its `engine.load` resolves. While set, all
    /// engine events are dropped in `handleEngineEvent` — they belong to the outgoing book or to
    /// the muted preroll, never to the freshly-buffering incoming book. Decoupled from the
    /// `.preparing` phase so the UI can show the honest `.buffering(duration)` state *during* the
    /// load while these events are still gated.
    private var isEngineLoading = false
    /// True only while playback is paused *because of* an audio-session
    /// interruption. Gates `.ended + .shouldResume`: resume is honored only
    /// when the interruption did the pausing — a user's deliberate pause is
    /// never overridden (Apple's "was playing when interrupted" contract).
    private var pausedByInterruption = false
    private static let positionReportIntervalMs: Int64 = 5000
    /// Feeds the shared "playback preempts downloads" signal. Optional so unit tests can construct
    /// the coordinator without a Kotlin coordinator.
    private let bandwidthCoordinator: PlaybackBandwidthCoordinator?
    /// Whether the currently-loaded book streams (has a non-local file) — gates the yield signal so
    /// a fully-downloaded book (no bandwidth contention) never asks downloads to back off.
    private var isCurrentBookStreaming = false

    // MARK: - Init

    init(
        preparer: PlaybackPreparing,
        progress: PlaybackProgressReporting,
        sleep: SleepTiming,
        engine: PlaybackEngine,
        documentProvider: BookDocumentProviding = NoDocumentProviding(),
        skipIntervals: SkipIntervalProviding? = nil,
        fadeStepDelay: Duration = .milliseconds(250),
        bandwidthCoordinator: PlaybackBandwidthCoordinator? = nil
    ) {
        self.preparer = preparer
        self.progress = progress
        self.sleep = sleep
        self.engine = engine
        self.documentProvider = documentProvider
        self.skipIntervals = skipIntervals
        self.fadeStepDelay = fadeStepDelay
        self.bandwidthCoordinator = bandwidthCoordinator
        system.attach(handler: self)
        system.updateSkipIntervals(forwardSeconds: skipForwardSec, backwardSeconds: skipBackwardSec)
        bridge.bind(engine.events) { [weak self] in self?.handleEngineEvent($0) }
        observeSleep()
        observeSkipIntervals()
        observeInterruptions()
        observeRouteChanges()
    }

    // App-lifetime singleton, so this effectively never fires — uniform teardown shape.
    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    /// Convenience initializer using `Dependencies` — wires the Kotlin adapters.
    convenience init(deps: Dependencies) {
        self.init(
            preparer: KotlinPlaybackPreparing(preparer: deps.playbackPreparer),
            progress: KotlinProgressReporting(reporter: deps.playbackProgressReporter),
            sleep: KotlinSleepTiming(manager: deps.sleepTimerManager),
            engine: AudioEngine(),
            documentProvider: KotlinBookDocumentProviding(repository: deps.documentRepository),
            skipIntervals: KotlinSkipIntervalProviding(preferences: deps.playbackPreferences),
            bandwidthCoordinator: deps.playbackBandwidthCoordinator
        )
    }

    private func observeSleep() {
        bridge.bind(sleep.stateStream) { [weak self] state in self?.applySleepTimer(state) }
        bridge.bind(sleep.fired) { [weak self] _ in
            Task { @MainActor in await self?.handleSleepFired() }
        }
    }

    /// Track the user's skip-interval settings. Each stream emits the current value on
    /// subscribe and again on every change, so the `@Observable` surface the transport
    /// reads — and the lock-screen control — stay live with the setting.
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
        bridge.bind(NotificationCenter.default.notifications(named: name)) { [weak self] note in
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
        bridge.bind(NotificationCenter.default.notifications(named: name)) { [weak self] note in
            guard let self else { return }
            guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  let reason = AVAudioSession.RouteChangeReason(rawValue: raw),
                  RouteChangePolicy.shouldPause(reason: reason) else { return }
            Task { @MainActor in await self.applyInterruption(.pause) }
        }
    }

    private func applyInterruption(_ action: InterruptionPolicy.Action) async {
        guard let loaded = phase.playingState else { return }
        switch action {
        case .pause:
            // .playing OR .buffering: both mean audio intent is "advancing".
            if phase.isPlaying || isBuffering {
                pausedByInterruption = true
                await engine.pause()
                phase = .paused(loaded)
                progress.onPlaybackPaused(bookId: loaded.bookId, positionMs: bookPositionMs, speed: playbackSpeed)
                updateNowPlaying()
            }
        case .resume:
            guard pausedByInterruption else { return }
            pausedByInterruption = false
            if !phase.isPlaying {
                await engine.activateSession()
                await engine.play()
                phase = .playing(loaded)
                progress.onPlaybackStarted(bookId: loaded.bookId, positionMs: bookPositionMs, speed: playbackSpeed)
                updateNowPlaying()
            }
        case .none:
            break
        }
    }

    /// Timer reached zero: fade output to silence, pause, then tell the manager
    /// the fade is done so it resets to Inactive.
    private func handleSleepFired() async {
        guard !isFading else { return }
        isFading = true
        defer { isFading = false }
        guard let loaded = phase.playingState else { sleep.onFadeCompleted(); return }
        // Linear fade over ~3s.
        let steps = 12
        for step in stride(from: steps - 1, through: 0, by: -1) {
            await engine.setVolume(Float(step) / Float(steps))
            try? await Task.sleep(for: fadeStepDelay)
        }
        await engine.pause()
        await engine.setVolume(1.0) // restore for next play
        phase = .paused(loaded)
        progress.onPlaybackPaused(bookId: loaded.bookId, positionMs: bookPositionMs, speed: playbackSpeed)
        updateNowPlaying()
        sleep.onFadeCompleted()
    }

    // MARK: - Actions

    /// Prepare and start playback of a book. Safe to call while another book is playing or
    /// still preparing: the in-flight prepare is cancelled, the outgoing book's position is
    /// saved, the metadata surface is reset synchronously, and the engine is silenced before
    /// the new book loads — so a switch never leaks the old book's cover/chapters/audio (RC-1, RC-4).
    func play(bookId: String) {
        pausedByInterruption = false

        // Cancel any in-flight prepare and open a new epoch so its post-`await` continuations bail.
        prepareTask?.cancel()
        loadGeneration &+= 1
        let generation = loadGeneration
        // Gate engine events for the whole switch→load window (see `isEngineLoading`).
        isEngineLoading = true
        // Not known to stream until prepare resolves the timeline — conservative so a switch never
        // asks downloads to yield for a book that turns out to be fully local.
        isCurrentBookStreaming = false

        // Save the outgoing book's place before we retarget — a switch must not lose it.
        // Only silence the engine when there *was* an outgoing loaded book: an unconditional
        // pause on a fresh (idle) play is a no-op on the real engine, but pre-fires the test
        // engine's pause signal and desynchronizes the interruption fixtures.
        let hadLoadedBook = phase.playingState != nil
        if let outgoing = phase.playingState {
            progress.onPlaybackPaused(bookId: outgoing.bookId, positionMs: bookPositionMs, speed: playbackSpeed)
        }

        // RC-1(a): clear the metadata surface synchronously, before the async prepare, so that
        // during `.preparing` the UI never renders book A's cover/chapters/title against book B's id.
        resetMetadataForSwitch()

        phase = .preparing(PreparingState(bookId: bookId))
        currentBookId = bookId

        prepareTask = Task {
            // Silence the outgoing book immediately — before the (possibly slow) prepare —
            // so switching never leaves the previous audio playing under the new metadata.
            if hadLoadedBook { await engine.pause() }
            await prepareAndStart(bookId: bookId, generation: generation)
        }
    }

    /// Reset the observable metadata surface and position state for a fresh load. Keeps
    /// `playbackSpeed` (re-derived from the prepared book) to avoid a flash back to 1.0×.
    private func resetMetadataForSwitch() {
        bookTitle = ""
        authorName = ""
        narratorName = ""
        coverPath = nil
        chapters = []
        firstPdfDocId = nil
        documentToOpen = nil
        positionTracker.reset()
        lastReportedPositionMs = 0
        lastSyncedChapterIndex = -1
    }

    /// Toggle between play and pause. In `.error`, retries the errored book so the user is
    /// never stranded on a dead player (RC-5).
    func togglePlayback() {
        pausedByInterruption = false
        // Recovery: a tap on an errored book starts a fresh load rather than no-oping.
        if case .error(let errorState) = phase {
            if let bookId = errorState.bookId { play(bookId: bookId) }
            return
        }
        guard let loaded = phase.playingState else { return }
        // KMP value-class `BookId` is erased at the Swift boundary — its APIs take
        // the underlying id value directly.
        let id = loaded.bookId
        // Buffering counts as "active" — a toggle while buffering is a pause, not a resume.
        if isPlaybackActive {
            Task { await engine.pause() }
            phase = .paused(loaded)
            progress.onPlaybackPaused(bookId: id, positionMs: bookPositionMs, speed: playbackSpeed)
        } else {
            Task { await engine.play() }
            phase = .playing(loaded)
            progress.onPlaybackStarted(bookId: id, positionMs: bookPositionMs, speed: playbackSpeed)
        }
        updateNowPlaying()
    }

    /// Dismiss an errored player back to `.idle` — the user's escape so a failed load (e.g. offline)
    /// doesn't leave an undismissable error bar reserving space over every tab. No-op unless errored.
    func dismissError() {
        guard isErrored else { return }
        phase = .idle
        updateNowPlaying()
    }

    /// Seek to a whole-book position in milliseconds.
    func seekTo(positionMs: Int64) {
        // Capture the pre-seek position BEFORE issuing the seek so the span split gets a correct
        // "before" edge. The async engine seek hasn't moved the tracker yet at this point.
        let beforeMs = bookPositionMs
        Task { await engine.seek(toMs: positionMs) }
        // Report against the *loaded* book only — during `.preparing` the incoming book isn't
        // loaded yet, and reporting a seek for it would corrupt its untouched resume position.
        if let id = phase.playingState?.bookId {
            // Split the listening span (finalize pre-seek at `beforeMs`, reopen at `positionMs`) so
            // the jumped-over range isn't fabricated as listened content. This also persists the new
            // position — routing the seek through `onPositionUpdate` (which extends the open span
            // across the jump) is exactly the stats-corrupting bug this replaces.
            progress.onSeek(bookId: id, beforeMs: beforeMs, afterMs: positionMs, speed: playbackSpeed)
            lastReportedPositionMs = positionMs
        }
        updateNowPlaying()
    }

    /// Set the playback speed.
    func setSpeed(_ speed: Float) {
        playbackSpeed = speed
        Task { await engine.setRate(speed) }
        if let id = phase.playingState?.bookId {
            progress.onSpeedChanged(
                bookId: id, positionMs: bookPositionMs, newSpeed: speed
            )
        }
        updateNowPlaying()
    }

    /// Skip forward by the current interval (or an explicit override), clamped to the book's end.
    func skipForward(seconds: Int? = nil) {
        let interval = seconds ?? skipForwardSec
        seekTo(positionMs: min(bookPositionMs + Int64(interval) * 1000, bookDurationMs))
    }

    /// Skip backward by the current interval (or an explicit override), clamped to the book's start.
    func skipBackward(seconds: Int? = nil) {
        let interval = seconds ?? skipBackwardSec
        seekTo(positionMs: max(bookPositionMs - Int64(interval) * 1000, 0))
    }

    /// Jump to a chapter by index.
    func selectChapter(index: Int) {
        guard index >= 0, index < chapters.count else { return }
        seekTo(positionMs: chapters[index].startTime)
    }

    func setSleepTimer(minutes: Int) { sleep.setDurationTimer(minutes: minutes) }
    func setSleepTimerEndOfChapter() { sleep.setEndOfChapterTimer() }
    func cancelSleepTimer() { sleep.cancelTimer() }

    /// Download the current book's first PDF (if needed) and set `documentToOpen`
    /// to present `DocumentReaderView`. Audio playback is not affected.
    func openCurrentBookPdf() {
        guard let bookId = currentBookId, let docId = firstPdfDocId else { return }
        let title = bookTitle
        Task {
            if let path = await documentProvider.ensureLocalPath(bookId: bookId, docId: docId) {
                documentToOpen = ReaderDocument(localPath: path, title: title)
            }
        }
    }

    /// Persist the current position immediately. Called on pause, seek, and when
    /// the app backgrounds/terminates — the periodic 5s tick is not enough to
    /// guarantee the user's place survives a kill.
    func saveCurrentPosition() async {
        // Key on the *loaded* book, not `currentBookId`/`isVisible`: both are true during
        // `.preparing` (the incoming book), and saving position 0 there would zero its
        // untouched resume point before it ever starts.
        guard let id = phase.playingState?.bookId else { return }
        await progress.savePositionNow(bookId: id, positionMs: bookPositionMs)
    }

    /// Tear down all observation and release the engine. `async` so teardown is
    /// deterministic: the audio session is deactivated and the engine released
    /// *before* the call returns.
    func stop() async {
        pausedByInterruption = false
        // Supersede any in-flight `prepareAndStart`: `Task.cancel()` alone does not interrupt its
        // non-cancellation-checking `await`s (prepare, engine.load), so without bumping the epoch a
        // load that resolves after teardown would call `engine.play()` on the released engine and
        // resurrect `.playing`. Bumping `loadGeneration` makes it bail at its next `!isSuperseded`.
        prepareTask?.cancel()
        loadGeneration &+= 1
        isEngineLoading = false
        // Return to idle so the `phase` didSet releases the download-yield signal — otherwise a
        // stop while `.buffering` would leave a streaming book "buffering" forever and pin
        // `shouldYield` true, suspending iOS downloads indefinitely.
        phase = .idle
        bridge.cancelAll()
        positionTracker.reset()
        await engine.deactivateSession()
        await engine.release()
    }

    // MARK: - RemoteCommandHandler

    func remoteTogglePlayPause() { togglePlayback() }
    // Gate on `isPlaybackActive` (playing OR buffering) so the lock-screen play/pause
    // commands stay correct during the startup buffer — `isPlaying` alone would let a
    // remote "play" during buffering fall through to `togglePlayback` and pause instead.
    func remotePlay() { if !isPlaybackActive { togglePlayback() } }
    func remotePause() { if isPlaybackActive { togglePlayback() } }
    func remoteSkipForward() { skipForward() }
    func remoteSkipBackward() { skipBackward() }
    func remoteSeek(toMs positionMs: Int64) { seekTo(positionMs: positionMs) }

    // MARK: - Prepare

    private func prepareAndStart(bookId: String, generation: Int) async {
        guard let prepared = await preparer.prepare(bookId: bookId) else {
            guard !isSuperseded(generation) else { return }
            isEngineLoading = false
            phase = .error(ErrorState(message: "Couldn't start playback.", bookId: bookId))
            return
        }
        guard !isSuperseded(generation) else { return }

        bookTitle = prepared.bookTitle
        authorName = prepared.bookAuthor
        narratorName = prepared.bookNarrator
        coverPath = prepared.coverPath
        chapters = prepared.chapters
        playbackSpeed = prepared.resumeSpeed
        // RC-2: seed the tracker with the resume position (paused) so the UI shows the right
        // chapter/time immediately — before the engine's first real sample lands.
        positionTracker.update(positionMs: prepared.resumePositionMs, rate: 0)
        lastReportedPositionMs = prepared.resumePositionMs

        let segments = Self.resolveSegments(prepared.timeline.files)
        guard !segments.isEmpty else {
            guard !isSuperseded(generation) else { return }
            isEngineLoading = false
            phase = .error(ErrorState(message: "This book has no audio.", bookId: bookId))
            return
        }

        // Honest buffering UI: show the book with its REAL duration and a buffering state the
        // instant we know them — before the (possibly slow, streaming) load — instead of holding
        // the UI at "0m / preparing". Engine events stay gated by `isEngineLoading` through the
        // load, so this early `.buffering` never absorbs the outgoing book's or the muted preroll's
        // events. The first real `timeControlStatus == .playing` (after the gate lifts) promotes it
        // to `.playing`.
        // A book streams if any file has no local path — set BEFORE the phase change so the
        // `phase` didSet's bandwidth signal sees the right streaming-ness for this book.
        isCurrentBookStreaming = prepared.timeline.files.contains { $0.localPath == nil }
        phase = .buffering(PlayingState(bookId: bookId, durationMs: prepared.timeline.totalDurationMs))
        lastSyncedChapterIndex = chapterIndex
        updateNowPlaying()

        // PDF availability resolves off the audio-start path — it doesn't block playback, and
        // the cover already renders from `coverPath`. Guard stale results after a rapid switch.
        Task {
            let id = await documentProvider.firstPdfDocId(bookId: bookId)
            if bookId == currentBookId { firstPdfDocId = id }
        }

        // `load` awaits readiness and seeks to the resume position before returning, so
        // playback starts from the right place (RC-2). A `false` result means the load failed.
        let loaded = await engine.load(segments: segments, startPositionMs: prepared.resumePositionMs)
        guard !isSuperseded(generation) else { return }
        // The load is done — lift the event gate so the engine's real playing/buffering/position
        // events drive the phase from here on.
        isEngineLoading = false
        guard loaded else {
            // The incoming book's own load failure is reported here (RC-5). Its failure surfaces
            // via `load` returning false, not via a gated engine event.
            phase = .error(ErrorState(message: "Couldn't start playback.", bookId: bookId))
            return
        }

        await engine.setRate(prepared.resumeSpeed)
        guard !isSuperseded(generation) else { return }
        await engine.play()
        guard !isSuperseded(generation) else { return }

        // Report started only after the engine has actually been told to play, and only for the
        // book that survived the switch — so a superseded load never reports a phantom start.
        lastReportedPositionMs = prepared.resumePositionMs
        progress.onPlaybackStarted(bookId: bookId, positionMs: prepared.resumePositionMs, speed: prepared.resumeSpeed)
    }

    /// Whether a newer `play(bookId:)` has superseded the load that captured `generation`.
    private func isSuperseded(_ generation: Int) -> Bool {
        LoadGeneration.isSuperseded(taskGeneration: generation, current: loadGeneration)
    }

    /// Resolve the prepared timeline's files into playable `AudioSegment`s — the local file when
    /// downloaded, else the streaming URL; files with neither are dropped.
    private static func resolveSegments(_ files: [PreparedFile]) -> [AudioSegment] {
        files.compactMap { file -> AudioSegment? in
            let url: URL
            if let localPath = file.localPath {
                url = URL(fileURLWithPath: localPath)
            } else if let remote = URL(string: file.streamingUrl) {
                url = remote
            } else {
                return nil
            }
            return AudioSegment(url: url, durationMs: file.durationMs, offsetMs: file.startOffsetMs)
        }
    }

    // MARK: - Engine events

    private func handleEngineEvent(_ event: AudioEngineEvent) {
        // Drop any engine event that arrives while a book is loading (`isEngineLoading`): it
        // predates the incoming book's `engine.load` completing and belongs to the *outgoing*
        // book or to the muted preroll. Critically this covers `.failed` — a late failure KVO
        // from book A must not be attributed to book B and abort its start (RC-4). The incoming
        // book's own load failure surfaces via `engine.load` returning `false`, never via a gated
        // event. Gated on the flag, not the `.preparing` phase, so the honest `.buffering(duration)`
        // state can be shown *during* the load while these events stay suppressed.
        if isEngineLoading { return }
        switch event {
        case .position(let ms, let rate):
            // Only a loaded book has a place to report to (guards `.idle`/`.error` too).
            guard phase.playingState != nil else { break }
            positionTracker.update(positionMs: ms, rate: rate)
            reportPositionIfNeeded(ms)
            if chapterIndex != lastSyncedChapterIndex {
                lastSyncedChapterIndex = chapterIndex
                sleep.onChapterChanged(newChapterIndex: chapterIndex)
            }
        case .statusChanged(let status):
            applyEngineStatus(status)
        case .ended:
            handleBookEnded()
        case .failed(let message):
            // Attribute the failure to the phase's book, not `currentBookId` — during a switch
            // the two can differ, and the error must name the book the engine was actually loading.
            phase = .error(ErrorState(message: message, bookId: phase.bookId ?? currentBookId))
            updateNowPlaying()
        }
    }

    /// Collapse the engine's interleaved ready/buffering stream into phase transitions.
    /// `.ready` (timeControlStatus == .playing) promotes a buffering book to playing;
    /// `.buffering` demotes a playing book. A paused player ignores status churn.
    private func applyEngineStatus(_ status: AudioEngineStatus) {
        guard let loaded = phase.playingState else { return }
        switch status {
        case .buffering:
            if case .playing = phase {
                phase = .buffering(loaded)
                updateNowPlaying()
            }
        case .ready:
            if case .buffering = phase {
                phase = .playing(loaded)
                updateNowPlaying()
            }
        case .idle:
            break
        }
    }

    private func reportPositionIfNeeded(_ positionMs: Int64) {
        // Report against the *loaded* book, not `currentBookId` — the latter is retargeted
        // synchronously on switch, before the new book is loaded (RC-4).
        guard let id = phase.playingState?.bookId else { return }
        if abs(positionMs - lastReportedPositionMs) >= Self.positionReportIntervalMs {
            lastReportedPositionMs = positionMs
            progress.onPositionUpdate(
                bookId: id, positionMs: positionMs, speed: playbackSpeed
            )
        }
    }

    private func handleBookEnded() {
        guard let loaded = phase.playingState else { return }
        progress.onBookFinished(bookId: loaded.bookId, finalPositionMs: bookDurationMs)
        phase = .paused(loaded)
        updateNowPlaying()
    }

    // MARK: - Sleep timer

    private func applySleepTimer(_ state: SleepTimingState) {
        switch state {
        case .inactive:
            sleepTimerActive = false
            sleepTimerRemainingMs = 0
            sleepTimerMode = ""
            sleepTimerLabel = ""
        case let .active(remainingMs, isEndOfChapter, label):
            sleepTimerActive = true
            sleepTimerRemainingMs = remainingMs
            sleepTimerMode = isEndOfChapter ? "endOfChapter" : "duration"
            sleepTimerLabel = label
        }
    }

    // MARK: - Playback-preempts-downloads signal

    /// Tell the shared coordinator whether a STREAMING book is currently buffering, so background
    /// downloads yield only during the stalls that matter (a local book never asks them to). Driven
    /// by the `phase` didSet — `.preparing`/`.buffering` mean "loading/stalled".
    private func updateBandwidthSignal() {
        let buffering: Bool
        switch phase {
        case .preparing, .buffering: buffering = true
        case .idle, .playing, .paused, .error: buffering = false
        }
        bandwidthCoordinator?.setStreamingBuffering(active: buffering && isCurrentBookStreaming)
    }

    // MARK: - System integration

    private func updateNowPlaying() {
        // `.error` keeps the in-app player visible (for the inline retry) but has no now-playing
        // content — clear the lock-screen controls; the retry lives in the app.
        guard isVisible, !isErrored else {
            system.clear()
            return
        }
        system.update(NowPlayingInfo(
            title: chapterTitle ?? bookTitle,
            artist: authorName,
            durationMs: bookDurationMs,
            elapsedMs: bookPositionMs,
            // Report a live rate ONLY while audio is actually advancing (`.playing`) — not while
            // buffering. `MPNowPlayingInfoCenter` extrapolates the displayed elapsed time as
            // `elapsed + rate·wallclock`, so a non-zero rate during the (now much longer) pre-load
            // buffer would tick the lock-screen clock forward from the resume point and then snap
            // it back when real playback starts. Rate 0 while buffering keeps elapsed honest.
            rate: isPlaying ? Double(playbackSpeed) : 0,
            artworkPath: coverPath
        ))
    }
}
