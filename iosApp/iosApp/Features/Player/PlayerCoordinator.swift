import SwiftUI
@preconcurrency import Shared

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

    private let engine = AudioEngine()
    private let positionTracker = PositionTracker()
    private let system = SystemIntegration()

    // MARK: - KMP seam

    private let preparer: PlaybackPreparer
    private let progressTracker: ProgressTracker
    private let sleepTimerManager: SleepTimerManager
    private let bookRepository: BookRepository
    private let bridge = FlowBridge()

    private var currentBookId: String?
    private var lastReportedPositionMs: Int64 = 0
    private static let positionReportIntervalMs: Int64 = 5000

    // MARK: - Init

    init(
        preparer: PlaybackPreparer,
        progressTracker: ProgressTracker,
        sleepTimerManager: SleepTimerManager,
        bookRepository: BookRepository
    ) {
        self.preparer = preparer
        self.progressTracker = progressTracker
        self.sleepTimerManager = sleepTimerManager
        self.bookRepository = bookRepository
        system.attach(handler: self)
        bridge.bind(engine.events) { [weak self] in self?.handleEngineEvent($0) }
        bridge.bind(sleepTimerManager.state) { [weak self] in self?.applySleepTimer($0) }
    }

    /// Convenience initializer using `Dependencies`.
    convenience init(deps: Dependencies) {
        self.init(
            preparer: deps.playbackPreparer,
            progressTracker: deps.progressTracker,
            sleepTimerManager: deps.sleepTimerManager,
            bookRepository: deps.bookRepository
        )
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
            progressTracker.onPlaybackPaused(bookId: id, positionMs: bookPositionMs, speed: playbackSpeed)
        } else {
            Task { await engine.play() }
            phase = .playing(loaded)
            progressTracker.onPlaybackStarted(bookId: id, positionMs: bookPositionMs, speed: playbackSpeed)
        }
        updateNowPlaying()
    }

    /// Seek to a whole-book position in milliseconds.
    func seekTo(positionMs: Int64) {
        Task { await engine.seek(toMs: positionMs) }
        updateNowPlaying()
    }

    /// Set the playback speed.
    func setSpeed(_ speed: Float) {
        playbackSpeed = speed
        Task { await engine.setRate(speed) }
        if let id = currentBookId {
            progressTracker.onSpeedChanged(
                bookId: id, positionMs: bookPositionMs, newSpeed: speed
            )
        }
        updateNowPlaying()
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

    func setSleepTimer(minutes: Int) {
        sleepTimerManager.setTimer(mode: SleepTimerModeDuration(minutes: Int32(minutes)))
    }

    func setSleepTimerEndOfChapter() {
        sleepTimerManager.setTimer(mode: SleepTimerModeEndOfChapter())
    }

    func cancelSleepTimer() {
        sleepTimerManager.cancelTimer()
    }

    /// Tear down all observation and release the engine.
    func stop() {
        bridge.cancelAll()
        positionTracker.reset()
        Task { await engine.release() }
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
        let id = bookId
        guard let prepared = try? await preparer.prepare(bookId: id, onPrepareProgress: { @Sendable _ in }) else {
            phase = .error(ErrorState(message: "Couldn't start playback.", bookId: bookId))
            return
        }
        bookTitle = prepared.bookTitle
        authorName = prepared.bookAuthor
        coverPath = prepared.coverPath
        chapters = Array(prepared.chapters)
        playbackSpeed = prepared.resumeSpeed
        coverBlurHash = (try? await bookRepository.getBookListItem(id: bookId))?.coverBlurHash

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
        progressTracker.onPlaybackStarted(
            bookId: id, positionMs: prepared.resumePositionMs, speed: prepared.resumeSpeed
        )
        updateNowPlaying()
    }

    // MARK: - Engine events

    private func handleEngineEvent(_ event: AudioEngineEvent) {
        switch event {
        case .position(let ms, let rate):
            positionTracker.update(positionMs: ms, rate: rate)
            reportPositionIfNeeded(ms)
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
            progressTracker.onPositionUpdate(
                bookId: id, positionMs: positionMs, speed: playbackSpeed
            )
        }
    }

    private func handleBookEnded() {
        guard let id = currentBookId, let loaded = phase.playingState else { return }
        progressTracker.onBookFinished(bookId: id, finalPositionMs: bookDurationMs)
        phase = .paused(loaded)
        updateNowPlaying()
    }

    // MARK: - Sleep timer

    private func applySleepTimer(_ state: SleepTimerState) {
        if let active = state as? SleepTimerStateActive {
            sleepTimerActive = true
            sleepTimerRemainingMs = active.remainingMs
            if active.mode is SleepTimerModeDuration {
                sleepTimerMode = "duration"
                sleepTimerLabel = active.formatRemaining()
            } else {
                sleepTimerMode = "endOfChapter"
                sleepTimerLabel = "End of chapter"
            }
        } else {
            sleepTimerActive = false
            sleepTimerRemainingMs = 0
            sleepTimerMode = ""
            sleepTimerLabel = ""
        }
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
            rate: isPlaying ? Double(playbackSpeed) : 0
        ))
    }
}
