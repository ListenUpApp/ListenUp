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
    /// The chapter's own number, as the book counts it (1-based); not always `index + 1`.
    var number = 0
    /// Exactly one row is current: the chapter the playhead is in.
    var isCurrent = false
}

/// One series the loaded book is in, native (rule 8): where Go to Series goes, and the book's place there ("1", "2.5").
struct SeriesNavRef: Hashable, Sendable {
    let id: String
    let name: String
    let sequenceLabel: String?
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
}

/// The mini player's second line (spec §5.1), mapped once from the shared `MiniPlayerLine` (rule 8). A chapter is
/// named by the book's own `title`; `number` is set only for a chaptered book's untitled chapter; both nil is a
/// chapterless book. `sleepArmed` says a timer is running; the surface supplies its countdown, which follows the
/// progress (`PlayerSnapshot.sleepCountdownMs`).
enum MiniLine: Equatable, Sendable {
    case chapter(number: Int?, title: String?, sleepArmed: Bool)
    case paused(number: Int?, title: String?, sleepArmed: Bool)
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
                number: value.number.map { Int($0) }, title: value.title, sleepArmed: value.sleepArmed)
        case .paused(let paused):
            let value = paused.value
            self = .paused(
                number: value.number.map { Int($0) }, title: value.title, sleepArmed: value.sleepArmed)
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
    /// The current chapter's own number, as the book counts it (front matter included), for naming an untitled chapter.
    var chapterNumber = 1
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
    /// False in a chapterless book, where previous would only jump to 0:00 (the shared `canSkipToPreviousChapter`).
    var canSkipToPreviousChapter = false
    var playbackSpeed: Float = 1.0
    /// The user's default speed: the Speed sheet's reset goes back to it.
    var defaultSpeed: Float = 1.0
    var volumeBoostDb: Float = 0
    var defaultVolumeBoostDb: Float = 0
    var skipForwardSec = 30
    var skipBackwardSec = 10
    var sleepTimerActive = false
    var sleepTimerIsEndOfChapter = false
    /// A duration timer's own remainder. An end-of-chapter timer carries none: read `sleepCountdownMs` instead.
    var sleepTimerRemainingMs: Int64 = 0
    /// What a duration timer was armed with; 0 for end of chapter.
    var sleepTimerTotalMs: Int64 = 0
    var sleepTimerStartedAtMs: Int64 = 0
    /// Only a running duration timer can be extended (shared `SleepTimerChoices.canExtend`).
    var sleepTimerCanExtend = false
    /// End of Chapter is offered only for a chaptered book (shared `SleepTimerChoices.offersEndOfChapter`).
    var offersEndOfChapter = false
    /// The sleep timer is fading the audio out before it pauses.
    var isSleepFading = false
    /// Now Playing's status slot (§3.1): only news (offline, unreachable, transcoded, not playable), from the shared
    /// `SourceStatus`; nil hides it. Casting says nothing here: AirPlay's caption names the device.
    var statusText: String?
    /// The audio is going to another device.
    var isCasting = false
    /// Where the audio is playing while casting, for AirPlay's caption; nil while it plays here.
    var castDeviceName: String?
    /// The card that takes the transport's place (P32), decided in shared code; nil while the transport shows.
    var card: PlayerCardModel?
    /// The More menu (§5.5.7), from the shared builder.
    var menu: [PlayerMenuSection] = []
    /// The same menu where the series and the authors are already links on screen (iPad's wide player, P10): no Go to
    /// Series, no Go to Author.
    var menuWithoutSeries: [PlayerMenuSection] = []
    /// Now Playing closes: nothing is loaded, or the book was revoked (§5.6).
    var closesNowPlaying = false
    /// "Chapter 6 of 75" shows only beside a chapter's own, non-generic title, where the counter cannot disagree with
    /// the book's naming (Simon, 2026-10-14; plan C6). Never for a chapterless book.
    var showsChapterCounter = false
    /// A chapterless book: Now Playing reads "Whole Book".
    var isWholeBook = false
    /// Every series the book is in, in the book's order.
    var series: [SeriesNavRef] = []

    static func from(_ state: any PlayerUiState) -> PlayerSnapshot {
        guard case .loaded(let wrapped) = state.sealedType() else {
            var nothing = PlayerSnapshot()
            nothing.closesNowPlaying = true
            return nothing
        }
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
        snapshot.canSkipToPreviousChapter = loaded.canSkipToPreviousChapter
        let settings = loaded.settings
        snapshot.playbackSpeed = settings.speed
        snapshot.defaultSpeed = settings.defaultSpeed
        snapshot.volumeBoostDb = settings.boostDb
        snapshot.defaultVolumeBoostDb = settings.defaultBoostDb
        snapshot.skipForwardSec = Int(settings.skipForwardSec)
        snapshot.skipBackwardSec = Int(settings.skipBackSec)
        snapshot.applySleepTimer(loaded.sleepTimer)
        snapshot.applySource(loaded.source, status: loaded.status)
        snapshot.card = PlayerCards.shared.of(state: loaded).map(PlayerCardModel.init)
        snapshot.menu = PlayerMenuModel.sections(for: loaded, includesSeries: true, includesAuthor: true)
        snapshot.menuWithoutSeries = PlayerMenuModel.sections(for: loaded, includesSeries: false, includesAuthor: false)
        snapshot.closesNowPlaying = PlayerCards.shared.closesNowPlaying(state: loaded)
        return snapshot
    }

    private mutating func applySource(_ source: any AudioSource, status: (any SourceStatus)?) {
        isCasting = false
        castDeviceName = nil
        statusText = status.map { news in
            switch news.sealedType() {
            case .offline: String(localized: "player.np_status_offline")
            case .serverUnreachable: String(localized: "player.np_status_server_unreachable")
            case .streamingTranscoded: String(localized: "player.np_status_streaming_transcoded")
            case .notPlayable: String(localized: "player.np_status_not_playable")
            }
        }
        if case .casting(let casting) = source.sealedType() {
            isCasting = true
            castDeviceName = casting.value.deviceName
        }
    }

    private mutating func applyBook(_ book: PlayerBook) {
        currentBookId = book.id
        bookTitle = book.title
        authors = book.authors.map { ContributorNavRef(id: $0.id, name: $0.name, bookCount: Int($0.bookCount)) }
        narrators = book.narrators.map { ContributorNavRef(id: $0.id, name: $0.name, bookCount: Int($0.bookCount)) }
        authorName = authors.map(\.name).joined(separator: ", ")
        narratorName = narrators.map(\.name).joined(separator: ", ")
        seriesId = book.series.first?.id
        series = book.series.map { SeriesNavRef(id: $0.id, name: $0.name, sequenceLabel: $0.sequenceLabel) }
        coverPath = book.coverPath
        coverHash = book.coverHash
        firstPdfDocId = book.firstPdfDocId
        bookDurationMs = book.durationMs
    }

    private mutating func applyChapters(current: PlayerChapter, rows: [PlayerChapterRow]) {
        chapterRows = rows.map { row in
            ChapterRowModel(
                id: String(row.index), title: row.title, startMs: row.startMs, durationMs: row.durationMs,
                index: Int(row.index), number: Int(row.number), isCurrent: row.isCurrent)
        }
        offersEndOfChapter = SleepTimerChoices.shared.offersEndOfChapter(chapter: current)
        chapterIndex = Int(current.index)
        chapterNumber = Int(current.number)
        totalChapters = Int(current.total)
        chapterTitle = current.isWholeBook ? nil : current.title
        isWholeBook = current.isWholeBook
        showsChapterCounter = !current.isWholeBook && !current.isGenericTitle
        chapterStartMs = current.startMs
        chapterDurationMs = current.durationMs
    }

    private mutating func applyPhase(_ phase: any PlayerPhase) {
        switch phase.sealedType() {
        case .playing: isPlaying = true
        case .sleepFading:
            isPlaying = true
            isSleepFading = true
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

    /// The card's cause for `error`: `player.cause_<code>`, or nil when the catalog has none.
    static func cause(for error: any AppError) -> String? {
        let key = "player.cause_" + error.code.lowercased()
        let cause = String(localized: String.LocalizationValue(key))
        return cause == key ? nil : cause
    }

    /// The mini line for `error`: `player.mini_<code>`, else the card title (§4.6).
    static func miniTitle(for error: any AppError) -> String {
        let key = "player.mini_" + error.code.lowercased()
        let text = String(localized: String.LocalizationValue(key))
        return text == key ? title(for: error) : text
    }

    private mutating func applySleepTimer(_ state: any SleepTimerState) {
        sleepTimerCanExtend = SleepTimerChoices.shared.canExtend(timer: state)
        guard case .active(let wrapped) = state.sealedType() else { return }
        let active = wrapped.value
        let isEndOfChapter: Bool = if case .endOfChapter = active.mode.sealedType() { true } else { false }
        sleepTimerActive = true
        sleepTimerIsEndOfChapter = isEndOfChapter
        sleepTimerRemainingMs = active.remainingMs
        sleepTimerTotalMs = active.totalMs
        sleepTimerStartedAtMs = active.startedAt
    }

    /// Wall-clock milliseconds until the timer pauses playback, given the chapter's content remainder; nil with no
    /// timer. The shared rule (`SleepCountdowns`): a duration counts itself down, end of chapter is the chapter's
    /// remainder at the listening speed.
    func sleepCountdownMs(chapterRemainingMs: Int64) -> Int64? {
        guard sleepTimerActive else { return nil }
        return SleepCountdowns.shared.remainingMs(
            isEndOfChapter: sleepTimerIsEndOfChapter, durationRemainingMs: sleepTimerRemainingMs,
            chapterRemainingMs: chapterRemainingMs, speed: playbackSpeed)
    }
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
    // so its `PlayerViewModel` lives for the process. Both are nil only for a detached observer (tests, snapshots).
    private let viewModel: PlayerViewModel?
    private let coordinator: PlayerCoordinator?
    private let bridge = FlowBridge()

    #if DEBUG
    /// An observer frozen at `state`, bound to nothing: actions do nothing. For hosting Now Playing in tests and review
    /// snapshots without a player or an engine.
    static func detached(
        _ state: any PlayerUiState, progress: PlayerProgressRow = PlayerProgressRow()
    ) -> PlayerObserver {
        PlayerObserver(snapshot: PlayerSnapshot.from(state), progress: progress)
    }

    /// Moves a detached observer to `state`, as a new frame from the shared player would: a hosted test's playhead
    /// crossing into the next chapter.
    func replaceDetachedState(_ state: any PlayerUiState, progress: PlayerProgressRow) {
        snapshot = PlayerSnapshot.from(state)
        self.progress = progress
    }

    private init(snapshot: PlayerSnapshot, progress: PlayerProgressRow) {
        viewModel = nil
        coordinator = nil
        self.snapshot = snapshot
        self.progress = progress
    }
    #endif

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
    var primary: PlayerPrimary { snapshot.primary }
    var miniLine: MiniLine { snapshot.miniLine }
    var showsSkipBack: Bool { snapshot.showsSkipBack }
    var canSkipToNextChapter: Bool { snapshot.canSkipToNextChapter }
    var canSkipToPreviousChapter: Bool { snapshot.canSkipToPreviousChapter }
    var playbackSpeed: Float { snapshot.playbackSpeed }
    var defaultSpeed: Float { snapshot.defaultSpeed }
    var volumeBoostDb: Float { snapshot.volumeBoostDb }
    var defaultVolumeBoostDb: Float { snapshot.defaultVolumeBoostDb }
    var skipForwardSec: Int { snapshot.skipForwardSec }
    var skipBackwardSec: Int { snapshot.skipBackwardSec }
    var sleepTimerActive: Bool { snapshot.sleepTimerActive }
    var sleepTimerIsEndOfChapter: Bool { snapshot.sleepTimerIsEndOfChapter }
    var sleepTimerRemainingMs: Int64 { snapshot.sleepTimerRemainingMs }
    /// Wall-clock time until the running timer pauses, following the progress; nil with no timer.
    var sleepCountdownMs: Int64? { snapshot.sleepCountdownMs(chapterRemainingMs: progress.chapterRemainingMs) }
    /// The countdown as the settings row and VoiceOver read it ("23:41"); empty with no timer.
    var sleepTimerLabel: String { sleepCountdownMs.map { DurationFormatting.clock(ms: $0) } ?? "" }
    /// The book's remainder in wall time at the current speed: the Speed sheet's one wall-time figure (S10).
    var bookRemainingAtSpeedMs: Int64 {
        PlayerProgress(
            chapterPositionMs: progress.chapterPositionMs, chapterDurationMs: progress.chapterDurationMs,
            bookPositionMs: progress.bookPositionMs, bookDurationMs: progress.bookDurationMs
        ).bookRemainingAtSpeedMs(speed: snapshot.playbackSpeed)
    }
    var bookDurationMs: Int64 { snapshot.bookDurationMs }
    var statusText: String? { snapshot.statusText }
    var isCasting: Bool { snapshot.isCasting }
    var castDeviceName: String? { snapshot.castDeviceName }
    var card: PlayerCardModel? { snapshot.card }
    var menu: [PlayerMenuSection] { snapshot.menu }
    /// More without Go to Series, for where the series is a link above the title.
    var menuWithoutSeries: [PlayerMenuSection] { snapshot.menuWithoutSeries }
    var closesNowPlaying: Bool { snapshot.closesNowPlaying }
    var showsChapterCounter: Bool { snapshot.showsChapterCounter }
    var isWholeBook: Bool { snapshot.isWholeBook }
    var series: [SeriesNavRef] { snapshot.series }

    // MARK: - Position

    /// Whether the engine holds the loaded book, so its per-frame tracker is the position's source.
    private var isLive: Bool {
        guard let coordinator else { return false }
        return coordinator.currentBookId != nil && coordinator.currentBookId == snapshot.currentBookId
    }

    /// Fine, per-frame position — the scrubber's smooth thumb. The engine's display-link tracker while
    /// it holds the book; the shared 4 Hz progress otherwise (a restored book).
    var bookPositionMs: Int64 {
        isLive ? (coordinator?.livePositionMs ?? progress.bookPositionMs) : progress.bookPositionMs
    }

    /// Coarse, ~1×/sec position — for time labels and progress lines, so they don't redraw per frame.
    var displayBookPositionMs: Int64 {
        if isLive, let coordinator { return coordinator.displayPositionMs }
        return PositionQuantizer.displayMs(progress.bookPositionMs)
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
        viewModel?.playBook(bookId: BookId(value: bookId), startAtMs: startAtMs)
    }
    func togglePlayback() { viewModel?.playPause() }
    /// The one main button: play/pause, or Retry / Sign in / Open Inbox / Close as the state decides.
    func primaryAction() { viewModel?.onPrimary() }
    /// The mini player's main button: the shared primary, or the sleep fade's Keep Listening, which cancels the timer
    /// and restores full volume where the shared primary would pause (Simon, 2026-10-14).
    func miniPrimary(_ action: MiniPlayerAccessoryModel.PrimaryAction) {
        switch action {
        case .shared: viewModel?.onPrimary()
        case .keepListening: keepListening()
        }
    }
    func dismissError() { viewModel?.dismissError() }
    func retry() { viewModel?.retry() }
    func seekTo(positionMs: Int64) { viewModel?.seekTo(positionMs: positionMs) }
    func skipForward() { viewModel?.skipForward() }
    func skipBackward() { viewModel?.skipBack() }
    func previousChapter() { viewModel?.previousChapter() }
    func nextChapter() { viewModel?.nextChapter() }
    func selectChapter(index: Int) { viewModel?.seekToChapter(index: Int32(index)) }
    func setSpeed(_ speed: Float) { viewModel?.setSpeed(speed: speed) }
    /// One 0.05× step slower or faster, counted in the shared whole hundredths so repeated steps never drift.
    func stepSpeed(up: Bool) { viewModel?.stepSpeed(up: up) }
    /// Back to the user's default speed: the book's own speed is removed, not overwritten.
    func resetSpeed() { viewModel?.resetSpeed() }
    func setBoost(_ db: Float) { viewModel?.setBoost(boostDb: db) }
    func resetBoost() { viewModel?.resetBoost() }
    func setSleepTimer(minutes: Int) { viewModel?.setSleepTimer(mode: SleepTimerModeDuration(minutes: Int32(minutes))) }
    func setSleepTimerEndOfChapter() { viewModel?.setSleepTimer(mode: SleepTimerModeEndOfChapter.shared) }
    func extendSleepTimer(minutes: Int) { viewModel?.extendSleepTimer(minutes: Int32(minutes)) }
    func cancelSleepTimer() { viewModel?.cancelSleepTimer() }
    /// The fade's Keep Listening: the timer is cancelled and the volume restored, where the primary would pause.
    func keepListening() { viewModel?.cancelSleepTimer() }
    func openCurrentBookPdf() { viewModel?.openCurrentPdf() }
    func closeBook() { viewModel?.closeBook() }
    /// A state card's button: the shared dispatcher carries it out (Retry, Dismiss, Close Book, Sign In, …).
    func onCardAction(_ action: PlayerCardModel.Action) { viewModel?.onCardAction(action: action.kotlin) }
    /// The finished card's rating, in half stars (2...10).
    func rate(halfStars: Int) { viewModel?.rate(halfStars: Int32(halfStars)) }
    /// Go to the loaded book's detail; the shared player emits the navigation.
    func goToBook() { viewModel?.goToBook() }

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
