import Foundation
import ListenupContract
@preconcurrency import Shared

/// One chapter as the list surfaces consume it — a native Swift value type, so SwiftUI's diff never
/// re-reads a Swift-Export-bridged Kotlin type across the boundary (rule 8). `PlayerObserver` builds
/// them from the shared player's rows; the coordinator builds its own for the lock screen's chapter index.
struct ChapterRowModel: Identifiable, Equatable, Sendable {
    let id: String
    let title: String
    let startMs: Int64
    let durationMs: Int64
    /// The row's position in the list (0-based).
    var index = 0
    /// Exactly one row is current: the chapter the playhead is in.
    var isCurrent = false
}

/// The native primary control (spec §4.6): what the one big button does, decided in shared code.
enum PlayerPrimary: Sendable, Equatable {
    case pause, play, busy, retry, listenAgain, signIn, openInbox, close, tapToPlay

    init(_ control: any PrimaryControl) {
        self = switch control.sealedType() {
        case .pause: .pause
        case .play: .play
        case .busy: .busy
        case .retry: .retry
        case .listenAgain: .listenAgain
        case .signIn: .signIn
        case .openInbox: .openInbox
        case .close: .close
        case .tapToPlay: .tapToPlay
        }
    }

    /// The error surfaces' action button: what the primary does for a book that can't play.
    var errorActionTitle: String {
        switch self {
        case .signIn: String(localized: "auth.sign_in")
        case .openInbox: String(localized: "common.inbox")
        case .close: String(localized: "player.close")
        case .pause, .play, .busy, .retry, .listenAgain, .tapToPlay: String(localized: "player.retry")
        }
    }
}

/// The mini player's second line (spec §5.1), mapped once from the shared `MiniPlayerLine` (rule 8). A chapter is
/// named by the book's own `title`; `number` is set only for a chaptered book's untitled chapter; both nil is a
/// chapterless book.
enum MiniLine: Equatable, Sendable {
    case chapter(number: Int?, title: String?, sleepRemainingMs: Int64?)
    case paused(number: Int?, title: String?, sleepRemainingMs: Int64?)
    case casting(device: String, number: Int?, title: String?)
    case starting, loading, reconnecting, fadingOut, finished
    /// `title` is the short mini line (`player.mini_<code>`), falling back to the card title. `isTinted` marks a
    /// failure the reader has to act on.
    case problem(code: String, title: String, isTinted: Bool)

    init(_ line: any MiniPlayerLine) {
        switch line.sealedType() {
        case .chapter(let chapter):
            let value = chapter.value
            self = .chapter(
                number: value.number.map { Int($0) }, title: value.title, sleepRemainingMs: value.sleepRemainingMs)
        case .paused(let paused):
            let value = paused.value
            self = .paused(
                number: value.number.map { Int($0) }, title: value.title, sleepRemainingMs: value.sleepRemainingMs)
        case .casting(let casting):
            let value = casting.value
            self = .casting(device: value.deviceName, number: value.number.map { Int($0) }, title: value.title)
        case .starting: self = .starting
        case .loading: self = .loading
        case .reconnecting: self = .reconnecting
        case .fadingOut: self = .fadingOut
        case .finished: self = .finished
        case .problem(let problem):
            let value = problem.value
            self = .problem(
                code: value.error.code, title: PlayerSnapshot.miniTitle(for: value.error), isTinted: value.isTinted)
        }
    }
}

/// Native values the player surfaces render (rule 8). Built once per `uiState` emission, never on a
/// position tick (ticks are `PlayerProgressRow`).
struct PlayerSnapshot: Equatable {
    var isVisible = false
    var currentBookId: String?
    var bookTitle = ""
    var authorName = ""
    var narratorName = ""
    /// The first series the book is in (iOS shows one), for Go to series.
    var seriesId: String?
    var authors: [ContributorNavRef] = []
    var narrators: [ContributorNavRef] = []
    var coverPath: String?
    var coverHash: String?
    var firstPdfDocId: String?
    var bookDurationMs: Int64 = 0
    var chapterRows: [ChapterRowModel] = []
    var chapterIndex = 0
    /// A chapterless book is one whole-book chapter (§3.2), so this is never 0 for a loaded book.
    var totalChapters = 0
    /// The current chapter's title; nil for a chapterless book.
    var chapterTitle: String?
    var chapterStartMs: Int64 = 0
    var chapterDurationMs: Int64 = 0
    var isPlaying = false
    var isBuffering = false
    var isPreparing = false
    /// A failure, or a known reason the book can't play: the error surfaces show it with the primary action.
    var isErrored = false
    var errorCode: String?
    /// The error's card title (`player.title_<code>`), falling back to the error's own message.
    var errorMessage: String?
    var primary: PlayerPrimary = .play
    /// The mini player's second line, decided in shared code.
    var miniLine: MiniLine = .loading
    var showsSkipBack = false
    var canSkipToNextChapter = false
    var playbackSpeed: Float = 1.0
    var volumeBoostDb: Float = 0
    var defaultVolumeBoostDb: Float = 0
    var skipForwardSec = 30
    var skipBackwardSec = 10
    var sleepTimerActive = false
    var sleepTimerIsEndOfChapter = false
    var sleepTimerRemainingMs: Int64 = 0
    var sleepTimerLabel = ""

    static func from(_ state: any PlayerUiState) -> PlayerSnapshot {
        guard case .loaded(let wrapped) = state.sealedType() else { return PlayerSnapshot() }
        let loaded = wrapped.value
        var snapshot = PlayerSnapshot()
        snapshot.isVisible = true
        snapshot.applyBook(loaded.book)
        snapshot.applyChapters(current: loaded.chapter, rows: loaded.chapters)
        snapshot.applyPhase(loaded.phase)
        snapshot.primary = PlayerPrimary(loaded.primary)
        snapshot.miniLine = MiniLine(MiniPlayerLines.shared.of(state: loaded))
        snapshot.showsSkipBack = loaded.showsSkipBack
        snapshot.canSkipToNextChapter = loaded.canSkipToNextChapter
        let settings = loaded.settings
        snapshot.playbackSpeed = settings.speed
        snapshot.volumeBoostDb = settings.boostDb
        snapshot.defaultVolumeBoostDb = settings.defaultBoostDb
        snapshot.skipForwardSec = Int(settings.skipForwardSec)
        snapshot.skipBackwardSec = Int(settings.skipBackSec)
        snapshot.applySleepTimer(loaded.sleepTimer)
        return snapshot
    }

    private mutating func applyBook(_ book: PlayerBook) {
        currentBookId = book.id
        bookTitle = book.title
        authors = book.authors.map { ContributorNavRef(id: $0.id, name: $0.name) }
        narrators = book.narrators.map { ContributorNavRef(id: $0.id, name: $0.name) }
        authorName = authors.map(\.name).joined(separator: ", ")
        narratorName = narrators.map(\.name).joined(separator: ", ")
        seriesId = book.series.first?.id
        coverPath = book.coverPath
        coverHash = book.coverHash
        firstPdfDocId = book.firstPdfDocId
        bookDurationMs = book.durationMs
    }

    private mutating func applyChapters(current: PlayerChapter, rows: [PlayerChapterRow]) {
        chapterRows = rows.map { row in
            ChapterRowModel(
                id: String(row.index), title: row.title, startMs: row.startMs, durationMs: row.durationMs,
                index: Int(row.index), isCurrent: row.isCurrent)
        }
        chapterIndex = Int(current.index)
        totalChapters = Int(current.total)
        chapterTitle = current.isWholeBook ? nil : current.title
        chapterStartMs = current.startMs
        chapterDurationMs = current.durationMs
    }

    private mutating func applyPhase(_ phase: any PlayerPhase) {
        switch phase.sealedType() {
        case .playing, .sleepFading: isPlaying = true
        // A network drop inside its grace draws as buffering until the player redesign gives it its own look.
        case .buffering, .reconnecting: isBuffering = true
        case .preparing: isPreparing = true
        case .failed(let failed): applyError(failed.value.error)
        case .unavailable(let unavailable): applyError(unavailable.value.reason)
        case .restored, .paused, .finished: break
        }
    }

    private mutating func applyError(_ error: any AppError) {
        isErrored = true
        errorCode = error.code
        errorMessage = Self.title(for: error)
    }

    /// The card title for `error` (spec §4.6, Decision 7): `player.title_<code>`, else its own message.
    static func title(for error: any AppError) -> String {
        let key = "player.title_" + error.code.lowercased()
        let title = String(localized: String.LocalizationValue(key))
        return title == key ? error.message : title
    }

    /// The mini line for `error`: `player.mini_<code>`, else the card title (§4.6).
    static func miniTitle(for error: any AppError) -> String {
        let key = "player.mini_" + error.code.lowercased()
        let text = String(localized: String.LocalizationValue(key))
        return text == key ? title(for: error) : text
    }

    private mutating func applySleepTimer(_ state: any SleepTimerState) {
        guard case .active(let wrapped) = state.sealedType() else { return }
        let active = wrapped.value
        let isEndOfChapter: Bool = if case .endOfChapter = active.mode.sealedType() { true } else { false }
        sleepTimerActive = true
        sleepTimerIsEndOfChapter = isEndOfChapter
        sleepTimerRemainingMs = active.remainingMs
        sleepTimerLabel = isEndOfChapter ? String(localized: "player.end_of_chapter") : active.formatRemaining()
    }
}

/// Native mirror of the shared `PlayerProgress` (~4 Hz): the four positions and what derives from them.
struct PlayerProgressRow: Equatable {
    var chapterPositionMs: Int64 = 0
    var chapterDurationMs: Int64 = 0
    var bookPositionMs: Int64 = 0
    var bookDurationMs: Int64 = 0

    init() {}

    init(_ progress: PlayerProgress) {
        chapterPositionMs = progress.chapterPositionMs
        chapterDurationMs = progress.chapterDurationMs
        bookPositionMs = progress.bookPositionMs
        bookDurationMs = progress.bookDurationMs
    }

    var chapterRemainingMs: Int64 { max(0, chapterDurationMs - chapterPositionMs) }
    var bookRemainingMs: Int64 { max(0, bookDurationMs - bookPositionMs) }
    var bookFraction: Float { bookDurationMs > 0 ? min(1, max(0, Float(bookPositionMs) / Float(bookDurationMs))) : 0 }
}

/// The SwiftUI model of the shared player (spec S1): one `PlayerViewModel`, mapped to native values once
/// at this boundary (rule 8). Every player surface — the mini player, Now Playing, the reader strip,
/// CarPlay, the Live Activity, Book and Series Detail — reads this and acts through it.
@Observable
@MainActor
final class PlayerObserver {
    private(set) var snapshot = PlayerSnapshot()
    private(set) var progress = PlayerProgressRow()
    /// The book a play request is in flight for (debounced): Book Detail's busy button.
    private(set) var preparingBookId: String?
    /// Set when a PDF is ready to present; cleared on dismiss. Drives `.fullScreenCover(item:)`.
    var documentToOpen: ReaderDocument?
    /// A book the shared player asked to open (Go to book, Play next book's navigation).
    var bookToOpen: String?
    /// The SessionExpired primary asked for sign-in.
    var signInRequested = false
    /// The held-book primary (admins) asked for the Inbox.
    var inboxRequested = false

    // view-model-owner: nobody — the observer is an app-lifetime singleton (`Dependencies.playerObserver`),
    // so its `PlayerViewModel` lives for the process.
    private let viewModel: PlayerViewModel
    private let coordinator: PlayerCoordinator
    private let bridge = FlowBridge()

    init(viewModel: PlayerViewModel, coordinator: PlayerCoordinator) {
        self.viewModel = viewModel
        self.coordinator = coordinator
        bridge.bind(viewModel.uiState) { [weak self] state in
            let next = PlayerSnapshot.from(state)
            if self?.snapshot != next { self?.snapshot = next }
        }
        bridge.bind(viewModel.progress) { [weak self] in self?.progress = PlayerProgressRow($0) }
        bridge.bind(viewModel.preparingBookId) { [weak self] in self?.preparingBookId = $0 }
        bridge.bind(viewModel.events) { [weak self] in self?.handle($0) }
    }

    // MARK: - What the surfaces read (the names they read from the coordinator before)

    var isVisible: Bool { snapshot.isVisible }
    var currentBookId: String? { snapshot.currentBookId }
    var bookTitle: String { snapshot.bookTitle }
    var authorName: String { snapshot.authorName }
    var narratorName: String { snapshot.narratorName }
    var seriesId: String? { snapshot.seriesId }
    var authors: [ContributorNavRef] { snapshot.authors }
    var narrators: [ContributorNavRef] { snapshot.narrators }
    var coverPath: String? { snapshot.coverPath }
    var coverHash: String? { snapshot.coverHash }
    var firstPdfDocId: String? { snapshot.firstPdfDocId }
    var chapterRows: [ChapterRowModel] { snapshot.chapterRows }
    var chapterIndex: Int { snapshot.chapterIndex }
    var totalChapters: Int { snapshot.totalChapters }
    var chapterTitle: String? { snapshot.chapterTitle }
    var chapterDurationMs: Int64 { snapshot.chapterDurationMs }
    var chapterStartMs: Int64 { snapshot.chapterStartMs }
    var isPlaying: Bool { snapshot.isPlaying }
    var isBuffering: Bool { snapshot.isBuffering }
    var isPreparing: Bool { snapshot.isPreparing }
    /// Audio is playing or buffering toward playing: a tap pauses.
    var isPlaybackActive: Bool { snapshot.isPlaying || snapshot.isBuffering }
    var isErrored: Bool { snapshot.isErrored }
    var errorMessage: String? { snapshot.errorMessage }
    var primary: PlayerPrimary { snapshot.primary }
    var miniLine: MiniLine { snapshot.miniLine }
    var showsSkipBack: Bool { snapshot.showsSkipBack }
    var canSkipToNextChapter: Bool { snapshot.canSkipToNextChapter }
    var playbackSpeed: Float { snapshot.playbackSpeed }
    var volumeBoostDb: Float { snapshot.volumeBoostDb }
    var defaultVolumeBoostDb: Float { snapshot.defaultVolumeBoostDb }
    var skipForwardSec: Int { snapshot.skipForwardSec }
    var skipBackwardSec: Int { snapshot.skipBackwardSec }
    var sleepTimerActive: Bool { snapshot.sleepTimerActive }
    var sleepTimerIsEndOfChapter: Bool { snapshot.sleepTimerIsEndOfChapter }
    var sleepTimerRemainingMs: Int64 { snapshot.sleepTimerRemainingMs }
    var sleepTimerLabel: String { snapshot.sleepTimerLabel }
    var bookDurationMs: Int64 { snapshot.bookDurationMs }

    // MARK: - Position

    /// Whether the engine holds the loaded book, so its per-frame tracker is the position's source.
    private var isLive: Bool { coordinator.currentBookId != nil && coordinator.currentBookId == snapshot.currentBookId }

    /// Fine, per-frame position — the scrubber's smooth thumb. The engine's display-link tracker while
    /// it holds the book; the shared 4 Hz progress otherwise (a restored book).
    var bookPositionMs: Int64 {
        isLive ? (coordinator.livePositionMs ?? progress.bookPositionMs) : progress.bookPositionMs
    }

    /// Coarse, ~1×/sec position — for time labels and progress lines, so they don't redraw per frame.
    var displayBookPositionMs: Int64 {
        isLive ? coordinator.displayPositionMs : PositionQuantizer.displayMs(progress.bookPositionMs)
    }

    var chapterPositionMs: Int64 { clampedInChapter(bookPositionMs) }
    var displayChapterPositionMs: Int64 { clampedInChapter(displayBookPositionMs) }

    var displayBookProgress: Float {
        bookDurationMs > 0 ? min(1, Float(displayBookPositionMs) / Float(bookDurationMs)) : 0
    }

    private func clampedInChapter(_ positionMs: Int64) -> Int64 {
        min(max(0, positionMs - chapterStartMs), max(0, chapterDurationMs))
    }

    // MARK: - Actions (each forwards to the shared player)

    func play(bookId: String, startAtMs: Int64? = nil) {
        viewModel.playBook(bookId: BookId(value: bookId), startAtMs: startAtMs)
    }
    func togglePlayback() { viewModel.playPause() }
    /// The one main button: play/pause, or Retry / Sign in / Open Inbox / Close as the state decides.
    func primaryAction() { viewModel.onPrimary() }
    func dismissError() { viewModel.dismissError() }
    func retry() { viewModel.retry() }
    func seekTo(positionMs: Int64) { viewModel.seekTo(positionMs: positionMs) }
    func skipForward() { viewModel.skipForward() }
    func skipBackward() { viewModel.skipBack() }
    func previousChapter() { viewModel.previousChapter() }
    func nextChapter() { viewModel.nextChapter() }
    func selectChapter(index: Int) { viewModel.seekToChapter(index: Int32(index)) }
    func setSpeed(_ speed: Float) { viewModel.setSpeed(speed: speed) }
    func setBoost(_ db: Float) { viewModel.setBoost(boostDb: db) }
    func resetBoost() { viewModel.resetBoost() }
    func setSleepTimer(minutes: Int) { viewModel.setSleepTimer(mode: SleepTimerModeDuration(minutes: Int32(minutes))) }
    func setSleepTimerEndOfChapter() { viewModel.setSleepTimer(mode: SleepTimerModeEndOfChapter.shared) }
    func extendSleepTimer(minutes: Int) { viewModel.extendSleepTimer(minutes: Int32(minutes)) }
    func cancelSleepTimer() { viewModel.cancelSleepTimer() }
    func openCurrentBookPdf() { viewModel.openCurrentPdf() }
    func closeBook() { viewModel.closeBook() }

    private func handle(_ event: any PlayerEvent) {
        switch event.sealedType() {
        case .openDocumentViewer(let open):
            documentToOpen = ReaderDocument(localPath: open.value.localPath, title: snapshot.bookTitle)
        case .navigateToBook(let navigate): bookToOpen = navigate.value.bookId
        case .openSignIn: signInRequested = true
        case .openInbox: inboxRequested = true
        }
    }
}
