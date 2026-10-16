import Foundation
import ListenupContract
import Shared

/// Observes `HomeViewModel` — flattens the sealed `HomeUiState` into a SwiftUI-native
/// `HomePhase`, and surfaces a Continue Listening failure as a clearable inline error.
/// Thin over `FlowBridge`; all mapping logic lives in pure, testable initializers.
@Observable
@MainActor
final class HomeViewModelWrapper {
    // MARK: - State

    private(set) var phase: HomePhase = .loading

    /// The Continue Listening failure carried on `Ready` (a part of Home that failed to load while
    /// the rest stayed up). iOS shows it inline, where the missing content would be, until the next
    /// refresh — not as a snackbar (iosApp rule 10).
    private(set) var inlineError: String?

    // MARK: - Dependencies

    private let viewModel: HomeViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: HomeViewModel = Dependencies.shared.makeHomeViewModel()) {
        self.viewModel = viewModel
        bridge.own(viewModel)
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    // MARK: - Actions

    /// Refresh, clearing the last inline failure — a retry that fails again says so again.
    func refresh() {
        inlineError = nil
        viewModel.refresh()
    }

    /// Dismiss a book's just-finished card on every device. Drawn in Home PR 4.
    func dismissFinished(bookId: String) {
        viewModel.dismissFinished(bookId: bookId)
    }

    /// Rate a book from its just-finished card, in half stars (2...10). Drawn in Home PR 4.
    func rateFinished(bookId: String, halfStars: Int) {
        viewModel.rateFinished(bookId: bookId, halfStars: Int32(halfStars))
    }

    // MARK: - State mapping

    private func apply(_ state: HomeUiState) {
        switch state.sealedType() {
        case .loading:
            phase = .loading
        case .ready(let readyType):
            let ready = HomeReady(from: readyType.value)
            phase = .ready(ready)
            inlineError = ready.continueListeningError
        case .error(let errorType):
            phase = .error(errorType.value.error.message)
        }
    }
}

// MARK: - Phase

/// Flattened Home state for a SwiftUI `switch` — the screen has three distinct surfaces.
enum HomePhase: Equatable {
    case loading
    case ready(HomeReady)
    case error(String)
}

// MARK: - Ready content

/// Everything the Home screen renders when `HomeUiState.Ready` is active.
struct HomeReady: Equatable {
    /// The bare time-of-day greeting ("Good evening") — *not* combined with the name. The KMP
    /// `Ready.greeting` getter appends the name; the header renders the name separately as a hero,
    /// so we map the bare `timeGreeting` here to avoid showing the name twice.
    let timeGreeting: String
    let userName: String
    let continueItems: [ContinueItem]
    let shelves: [ShelfItem]
    let isSyncing: Bool
    let timeOfDay: HomeTimeOfDay
    let firstName: String?
    let slot: HomeSlotItem
    let alsoInProgress: [ContinueItem]
    let connectivity: HomeConnectivityState
    /// The Continue Listening failure's message while the rest of Home loaded, or nil.
    let continueListeningError: String?

    init(from ready: HomeUiStateReady) {
        self.timeGreeting = ready.timeGreeting
        self.userName = ready.userName
        self.continueItems = ready.continueListening.map(ContinueItem.init(from:))
        self.shelves = ready.myShelves.map(ShelfItem.init(from:))
        self.isSyncing = ready.isSyncing
        self.timeOfDay = HomeTimeOfDay(kmp: ready.timeOfDay)
        self.firstName = ready.firstName
        self.slot = HomeSlotItem(from: ready.slot)
        self.alsoInProgress = ready.alsoInProgress.map(ContinueItem.init(from:))
        self.connectivity = HomeConnectivityState(kmp: ready.connectivity)
        self.continueListeningError = ready.continueListeningError?.message
    }
}

// MARK: - Continue Listening item

/// One Continue-Listening card. A `Ready` KMP item maps to a fully-populated card;
/// a `Loading` item (sync in-flight) maps to a skeleton card (`isLoading == true`).
struct ContinueItem: Identifiable, Equatable {
    let id: String
    let title: String
    let author: String
    let coverPath: String?
    let coverHash: String?
    let progress: Double
    let progressPercent: Int
    let timeLeft: String
    /// What is left to hear, and the whole length (the hero's "11% of 45 hr, 30 min").
    let remainingMs: Int64
    let totalMs: Int64
    /// The narrators joined with ", ", or nil when the book names none.
    let narrators: String?
    /// When the reader last played it; nil for a skeleton.
    let lastPlayedAt: Date?
    /// Every file is on this device, so it plays with no server.
    let isDownloaded: Bool
    let isLoading: Bool

    init(from item: ContinueListeningItem) {
        switch item.sealedType() {
        case .ready(let readyType):
            self.init(book: readyType.value.book)
        case .loading(let loadingType):
            let loading = loadingType.value
            self.init(
                id: loading.bookId,
                title: "",
                author: "",
                coverPath: nil,
                coverHash: nil,
                progress: 0,
                progressPercent: 0,
                timeLeft: "",
                isLoading: true
            )
        }
    }

    init(
        id: String,
        title: String,
        author: String,
        coverPath: String?,
        coverHash: String?,
        progress: Double,
        progressPercent: Int,
        timeLeft: String,
        remainingMs: Int64 = 0,
        totalMs: Int64 = 0,
        narrators: String? = nil,
        lastPlayedAt: Date? = nil,
        isDownloaded: Bool = false,
        isLoading: Bool
    ) {
        self.id = id
        self.title = title
        self.author = author
        self.coverPath = coverPath
        self.coverHash = coverHash
        self.progress = progress
        self.progressPercent = progressPercent
        self.timeLeft = timeLeft
        self.remainingMs = remainingMs
        self.totalMs = totalMs
        self.narrators = narrators
        self.lastPlayedAt = lastPlayedAt
        self.isDownloaded = isDownloaded
        self.isLoading = isLoading
    }

    init(book: ContinueListeningBook) {
        self.init(
            id: book.bookId,
            title: book.title,
            author: book.authorNames,
            coverPath: book.coverPath,
            coverHash: book.coverHash,
            progress: Double(book.progress),
            progressPercent: Int(book.progressPercent),
            timeLeft: book.timeRemainingFormatted,
            remainingMs: book.timeRemainingMs,
            totalMs: book.totalDurationMs,
            narrators: book.narratorNames,
            lastPlayedAt: Date(timeIntervalSince1970: TimeInterval(book.lastPlayedAtMs) / 1000),
            isDownloaded: book.isDownloaded,
            isLoading: false
        )
    }
}

// MARK: - Shelf item

/// One "My Shelves" card.
struct ShelfItem: Identifiable, Equatable {
    let id: String
    let name: String
    let bookCount: Int
    let durationLabel: String
    let covers: [DiscoverShelfCover]
    let isPrivate: Bool
    let totalSeconds: Int

    init(from shelf: Shelf) {
        self.id = shelf.idString
        self.name = shelf.name
        self.bookCount = Int(shelf.bookCount)
        self.durationLabel = shelf.formattedDuration
        self.covers = shelf.covers.map(DiscoverShelfCover.init(from:))
        self.isPrivate = shelf.isPrivate
        self.totalSeconds = Int(shelf.totalDurationSeconds)
    }
}

// MARK: - Slot

/// The one slot under Home's greeting, flattened from the sealed `HomeSlot` (spec §2.2).
enum HomeSlotItem: Equatable {
    case absent
    case firstSync
    case resume(ResumeSlot)
    case justFinished(FinishedSlot)
    case nothingInProgress(NextBookItem?)
    case newListener(covers: [DiscoverShelfCover])

    init(from slot: HomeSlot) {
        switch slot.sealedType() {
        case .absent:
            self = .absent
        case .firstSync:
            self = .firstSync
        case .resume(let resumeType):
            self = .resume(ResumeSlot(from: resumeType.value))
        case .justFinished(let finishedType):
            self = .justFinished(FinishedSlot(from: finishedType.value.card))
        case .nothingInProgress(let nothingType):
            self = .nothingInProgress(nothingType.value.candidate.map(NextBookItem.init(from:)))
        case .newListener(let newType):
            self = .newListener(covers: newType.value.covers.map(DiscoverShelfCover.init(from:)))
        }
    }
}

/// The Resume slot: the book, its chapter, the next book, and whether it can play right now.
struct ResumeSlot: Equatable {
    let book: ContinueItem
    let next: NextBookItem?
    let chapterNumber: Int?
    let chapterTitle: String?
    let canPlay: Bool

    init(from resume: HomeSlotResume) {
        self.book = ContinueItem(book: resume.book)
        self.next = resume.next.map(NextBookItem.init(from:))
        self.chapterNumber = resume.chapter.map { Int($0.number) }
        self.chapterTitle = resume.chapter?.title
        self.canPlay = resume.canPlay
    }
}

/// The just-finished card (spec §2.3).
struct FinishedSlot: Equatable {
    let bookId: String
    let title: String
    let author: String
    let coverPath: String?
    let coverHash: String?
    let durationMs: Int64
    let startedAt: Date?
    let finishedAt: Date
    /// Your rating on the 2...10 half-star scale, or nil.
    let yourHalfStars: Int?
    let next: NextBookItem?
    let alsoFinishedNames: [String]
    let alsoFinishedOthers: Int

    init(from card: FinishedCard) {
        self.bookId = card.book.bookId
        self.title = card.book.title
        self.author = card.book.authorNames
        self.coverPath = card.book.coverPath
        self.coverHash = card.book.coverHash
        self.durationMs = card.book.durationMs
        self.startedAt = card.startedAtMs.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
        self.finishedAt = Date(timeIntervalSince1970: TimeInterval(card.finishedAtMs) / 1000)
        self.yourHalfStars = card.yourHalfStars.map { Int($0) }
        self.next = card.next.map(NextBookItem.init(from:))
        self.alsoFinishedNames = card.alsoFinished.names
        self.alsoFinishedOthers = Int(card.alsoFinished.othersCount)
    }
}

/// The next book in a series — Resume's "Next in the series", the card's Play next book, NothingInProgress's candidate.
struct NextBookItem: Equatable {
    let id: String
    let title: String
    let coverPath: String?
    let coverHash: String?
    let narrators: String?
    let seriesName: String
    let sequenceLabel: String?
    let durationMs: Int64

    init(from next: NextInSeries) {
        self.id = next.bookId
        self.title = next.title
        self.coverPath = next.coverPath
        self.coverHash = next.coverHash
        self.narrators = next.narratorNames
        self.seriesName = next.seriesName
        self.sequenceLabel = next.sequenceLabel
        self.durationMs = next.durationMs
    }
}

/// The greeting's part of the day; the view words it from the string catalog.
enum HomeTimeOfDay: Equatable {
    case morning, afternoon, evening, night

    init(kmp: TimeOfDay) {
        if kmp == TimeOfDay.Morning {
            self = .morning
        } else if kmp == TimeOfDay.Afternoon {
            self = .afternoon
        } else if kmp == TimeOfDay.Evening {
            self = .evening
        } else {
            self = .night
        }
    }
}

/// Whether Home can reach anything (spec §3.1).
enum HomeConnectivityState: Equatable {
    case online, offline, serverUnreachable

    init(kmp: HomeConnectivity) {
        if kmp == HomeConnectivity.Offline {
            self = .offline
        } else if kmp == HomeConnectivity.ServerUnreachable {
            self = .serverUnreachable
        } else {
            self = .online
        }
    }
}
