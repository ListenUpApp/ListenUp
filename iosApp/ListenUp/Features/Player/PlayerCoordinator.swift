import SwiftUI
import AVFoundation
@preconcurrency import Shared

/// Pure decision for an audio-session interruption — testable without notifications.
enum InterruptionPolicy {
    enum Action: Equatable { case pause, resume, none }
    static func action(type: AVAudioSession.InterruptionType, shouldResume: Bool) -> Action {
        switch type {
        case .began: return .pause
        case .ended: return shouldResume ? .resume : .none
        @unknown default: return .none
        }
    }
}

/// Pure chapter math — resolves a whole-book position to a chapter index.
/// Split out so it is testable without a coordinator.
enum ChapterMath {
    /// The index of the chapter containing `positionMs`, or `nil` for an empty
    /// list. A position past the last chapter clamps to the last index.
    static func index(forPositionMs positionMs: Int64, in chapters: [Chapter_]) -> Int? {
        guard !chapters.isEmpty else { return nil }
        for (index, chapter) in chapters.enumerated()
        where positionMs < chapter.startTime + chapter.duration {
            return index
        }
        return chapters.count - 1
    }
}

/// The iOS player orchestrator (Option B). Owns `PlayerPhase` and the player-core
/// components, maps the KMP seam, and exposes the flat `@Observable` surface the
/// player UI consumes. The only player file that imports `Shared`.
@Observable
@MainActor
final class PlayerCoordinator: RemoteCommandHandler {

    // MARK: - Runtime phase

    private(set) var phase: PlayerPhase = .idle

    // MARK: - Preserved UI surface — visibility & playback flags (derived from phase)

    /// A book is loaded — drives the mini player's visibility.
    var isVisible: Bool {
        switch phase {
        case .idle, .error: return false
        case .preparing, .playing, .paused, .buffering: return true
        }
    }

    var isPlaying: Bool { phase.isPlaying }

    var isBuffering: Bool {
        if case .buffering = phase { return true }
        return false
    }

    // MARK: - Preserved UI surface — book metadata (set once on load)

    private(set) var bookTitle: String = ""
    private(set) var authorName: String = ""
    private(set) var coverPath: String?
    private(set) var coverBlurHash: String?
    private(set) var playbackSpeed: Float = 1.0
    private(set) var chapters: [Chapter_] = []

    // MARK: - Preserved UI surface — position (derived from PositionTracker)

    var bookDurationMs: Int64 { phase.playingState?.durationMs ?? 0 }
    var bookPositionMs: Int64 { positionTracker.positionMs }
    var bookProgress: Float {
        bookDurationMs > 0 ? Float(bookPositionMs) / Float(bookDurationMs) : 0
    }

    // MARK: - Preserved UI surface — chapter (computed from chapters + position)

    var chapterIndex: Int { ChapterMath.index(forPositionMs: bookPositionMs, in: chapters) ?? 0 }
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
    private let liveActivity = LiveActivityManager()

    // MARK: - Seam (native protocols — see PlaybackSeam.swift)

    private let preparer: PlaybackPreparing
    private let progress: PlaybackProgressReporting
    private let sleep: SleepTiming
    private let coverProvider: BookCoverProviding
    private let bridge = FlowBridge()

    private var currentBookId: String?
    private var lastReportedPositionMs: Int64 = 0
    private var lastSyncedChapterIndex: Int = -1
    private var isFading = false
    private static let positionReportIntervalMs: Int64 = 5000

    // MARK: - Init

    init(
        preparer: PlaybackPreparing,
        progress: PlaybackProgressReporting,
        sleep: SleepTiming,
        engine: PlaybackEngine,
        coverProvider: BookCoverProviding
    ) {
        self.preparer = preparer
        self.progress = progress
        self.sleep = sleep
        self.engine = engine
        self.coverProvider = coverProvider
        system.attach(handler: self)
        bridge.bind(engine.events) { [weak self] in self?.handleEngineEvent($0) }
        observeSleep()
        observeInterruptions()
    }

    /// Convenience initializer using `Dependencies` — wires the Kotlin adapters.
    convenience init(deps: Dependencies) {
        self.init(
            preparer: KotlinPlaybackPreparing(preparer: deps.playbackPreparer),
            progress: KotlinProgressReporting(tracker: deps.progressTracker),
            sleep: KotlinSleepTiming(manager: deps.sleepTimerManager),
            engine: AudioEngine(),
            coverProvider: KotlinBookCoverProviding(repository: deps.bookRepository)
        )
    }

    private func observeSleep() {
        bridge.bind(sleep.stateStream) { [weak self] state in self?.applySleepTimer(state) }
        bridge.bind(sleep.fired) { [weak self] _ in
            Task { @MainActor in await self?.handleSleepFired() }
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

    private func applyInterruption(_ action: InterruptionPolicy.Action) async {
        guard let loaded = phase.playingState else { return }
        switch action {
        case .pause:
            if phase.isPlaying {
                await engine.pause()
                phase = .paused(loaded)
                progress.onPlaybackPaused(bookId: loaded.bookId, positionMs: bookPositionMs, speed: playbackSpeed)
                updateNowPlaying(); syncLiveActivity()
            }
        case .resume:
            if !phase.isPlaying {
                await engine.play()
                phase = .playing(loaded)
                progress.onPlaybackStarted(bookId: loaded.bookId, positionMs: bookPositionMs, speed: playbackSpeed)
                updateNowPlaying(); syncLiveActivity()
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
            try? await Task.sleep(for: .milliseconds(250))
        }
        await engine.pause()
        await engine.setVolume(1.0) // restore for next play
        phase = .paused(loaded)
        progress.onPlaybackPaused(bookId: loaded.bookId, positionMs: bookPositionMs, speed: playbackSpeed)
        updateNowPlaying()
        syncLiveActivity()
        sleep.onFadeCompleted()
    }

    // MARK: - Actions

    /// Prepare and start playback of a book.
    func play(bookId: String) {
        phase = .preparing(PreparingState(bookId: bookId))
        currentBookId = bookId
        Task { await prepareAndStart(bookId: bookId) }
    }

    /// Toggle between play and pause.
    func togglePlayback() {
        guard let loaded = phase.playingState else { return }
        // KMP value-class `BookId` is erased at the Swift boundary — its APIs take
        // the underlying id value directly.
        let id = loaded.bookId
        if phase.isPlaying {
            Task { await engine.pause() }
            phase = .paused(loaded)
            progress.onPlaybackPaused(bookId: id, positionMs: bookPositionMs, speed: playbackSpeed)
        } else {
            Task { await engine.play() }
            phase = .playing(loaded)
            progress.onPlaybackStarted(bookId: id, positionMs: bookPositionMs, speed: playbackSpeed)
        }
        updateNowPlaying()
        syncLiveActivity()
    }

    /// Seek to a whole-book position in milliseconds.
    func seekTo(positionMs: Int64) {
        Task { await engine.seek(toMs: positionMs) }
        if let id = currentBookId {
            progress.onPositionUpdate(bookId: id, positionMs: positionMs, speed: playbackSpeed)
            lastReportedPositionMs = positionMs
        }
        updateNowPlaying()
        syncLiveActivity()
    }

    /// Set the playback speed.
    func setSpeed(_ speed: Float) {
        playbackSpeed = speed
        Task { await engine.setRate(speed) }
        if let id = currentBookId {
            progress.onSpeedChanged(
                bookId: id, positionMs: bookPositionMs, newSpeed: speed
            )
        }
        updateNowPlaying()
        syncLiveActivity()
    }

    /// Skip forward, clamped to the book's end.
    func skipForward(seconds: Int = 10) {
        seekTo(positionMs: min(bookPositionMs + Int64(seconds) * 1000, bookDurationMs))
    }

    /// Skip backward, clamped to the book's start.
    func skipBackward(seconds: Int = 10) {
        seekTo(positionMs: max(bookPositionMs - Int64(seconds) * 1000, 0))
    }

    /// Jump to a chapter by index.
    func selectChapter(index: Int) {
        guard index >= 0, index < chapters.count else { return }
        seekTo(positionMs: chapters[index].startTime)
    }

    func setSleepTimer(minutes: Int) { sleep.setDurationTimer(minutes: minutes) }
    func setSleepTimerEndOfChapter() { sleep.setEndOfChapterTimer() }
    func cancelSleepTimer() { sleep.cancelTimer() }

    /// Persist the current position immediately. Called on pause, seek, and when
    /// the app backgrounds/terminates — the periodic 5s tick is not enough to
    /// guarantee the user's place survives a kill.
    func saveCurrentPosition() async {
        guard let id = currentBookId, isVisible else { return }
        await progress.savePositionNow(bookId: id, positionMs: bookPositionMs)
    }

    /// Tear down all observation and release the engine.
    func stop() {
        bridge.cancelAll()
        positionTracker.reset()
        Task {
            await engine.deactivateSession()
            await engine.release()
        }
        liveActivity.end()
    }

    // MARK: - RemoteCommandHandler

    func remoteTogglePlayPause() { togglePlayback() }
    func remotePlay() { if !isPlaying { togglePlayback() } }
    func remotePause() { if isPlaying { togglePlayback() } }
    func remoteSkipForward() { skipForward(seconds: SystemIntegration.skipIntervalSeconds) }
    func remoteSkipBackward() { skipBackward(seconds: SystemIntegration.skipIntervalSeconds) }
    func remoteSeek(toMs positionMs: Int64) { seekTo(positionMs: positionMs) }

    // MARK: - Prepare

    private func prepareAndStart(bookId: String) async {
        guard let prepared = await preparer.prepare(bookId: bookId) else {
            phase = .error(ErrorState(message: "Couldn't start playback.", bookId: bookId))
            return
        }
        bookTitle = prepared.bookTitle
        authorName = prepared.bookAuthor
        coverPath = prepared.coverPath
        chapters = prepared.chapters
        playbackSpeed = prepared.resumeSpeed
        coverBlurHash = await coverProvider.coverBlurHash(bookId: bookId)

        let segments = prepared.timeline.files.compactMap { file -> AudioSegment? in
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
        guard !segments.isEmpty else {
            phase = .error(ErrorState(message: "This book has no audio.", bookId: bookId))
            return
        }

        await engine.load(segments: segments, startPositionMs: prepared.resumePositionMs)
        await engine.setRate(prepared.resumeSpeed)
        await engine.play()
        phase = .playing(PlayingState(bookId: bookId, durationMs: prepared.timeline.totalDurationMs))
        lastReportedPositionMs = prepared.resumePositionMs
        progress.onPlaybackStarted(bookId: bookId, positionMs: prepared.resumePositionMs, speed: prepared.resumeSpeed)
        updateNowPlaying()
        lastSyncedChapterIndex = chapterIndex
        if let snapshot = liveActivitySnapshot() { liveActivity.start(snapshot) }
    }

    // MARK: - Engine events

    private func handleEngineEvent(_ event: AudioEngineEvent) {
        switch event {
        case .position(let ms, let rate):
            positionTracker.update(positionMs: ms, rate: rate)
            reportPositionIfNeeded(ms)
            if chapterIndex != lastSyncedChapterIndex {
                lastSyncedChapterIndex = chapterIndex
                sleep.onChapterChanged(newChapterIndex: chapterIndex)
                syncLiveActivity()
            }
        case .statusChanged(let status):
            applyEngineStatus(status)
        case .ended:
            handleBookEnded()
        case .failed(let message):
            phase = .error(ErrorState(message: message, bookId: currentBookId))
        }
    }

    /// Collapse the engine's interleaved ready/buffering stream: only the
    /// playing↔buffering pair transitions; a paused player ignores status churn.
    private func applyEngineStatus(_ status: AudioEngineStatus) {
        guard let loaded = phase.playingState else { return }
        switch status {
        case .buffering:
            if case .playing = phase { phase = .buffering(loaded) }
        case .ready:
            if case .buffering = phase { phase = .playing(loaded) }
        case .idle:
            break
        }
    }

    private func reportPositionIfNeeded(_ positionMs: Int64) {
        guard let id = currentBookId else { return }
        if abs(positionMs - lastReportedPositionMs) >= Self.positionReportIntervalMs {
            lastReportedPositionMs = positionMs
            progress.onPositionUpdate(
                bookId: id, positionMs: positionMs, speed: playbackSpeed
            )
        }
    }

    private func handleBookEnded() {
        guard let id = currentBookId, let loaded = phase.playingState else { return }
        progress.onBookFinished(bookId: id, finalPositionMs: bookDurationMs)
        phase = .paused(loaded)
        updateNowPlaying()
        syncLiveActivity()
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

    // MARK: - Live Activity

    /// A value snapshot of the playback state the Live Activity needs.
    private func liveActivitySnapshot() -> LiveActivitySnapshot? {
        guard let bookId = currentBookId else { return nil }
        return LiveActivitySnapshot(
            bookId: bookId,
            bookTitle: bookTitle,
            authorName: authorName,
            coverBlurHash: coverBlurHash,
            coverPath: coverPath,
            chapterTitle: chapterTitle ?? bookTitle,
            isPlaying: isPlaying,
            bookPositionMs: bookPositionMs,
            bookDurationMs: bookDurationMs,
            chapterPositionMs: chapterPositionMs,
            chapterDurationMs: chapterDurationMs
        )
    }

    /// Push the current state to the Live Activity, if one should be running.
    private func syncLiveActivity() {
        guard let snapshot = liveActivitySnapshot() else { return }
        liveActivity.sync(snapshot)
    }

    // MARK: - System integration

    private func updateNowPlaying() {
        guard isVisible else {
            system.clear()
            return
        }
        system.update(NowPlayingInfo(
            title: chapterTitle ?? bookTitle,
            artist: authorName,
            durationMs: bookDurationMs,
            elapsedMs: bookPositionMs,
            rate: isPlaying ? Double(playbackSpeed) : 0,
            artworkPath: coverPath
        ))
    }
}
