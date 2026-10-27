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

    /// Dismiss a book's just-finished card on every device.
    func dismissFinished(bookId: String) {
        viewModel.dismissFinished(bookId: bookId)
    }

    /// Rate a book from its just-finished card, in half stars (2...10).
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
            phase = HomePhase(error: errorType.value)
        }
    }
}

// MARK: - Phase

/// Flattened Home state for a SwiftUI `switch` — the screen has three distinct surfaces.
enum HomePhase: Equatable {
    case loading
    case ready(HomeReady)
    /// Home could not load; the greeting stays, from the part of the day (spec §3.3).
    case error(message: String, timeOfDay: HomeTimeOfDay)

    init(error: HomeUiStateError) {
        self = .error(message: error.error.message, timeOfDay: HomeTimeOfDay(kmp: error.timeOfDay))
    }
}

// MARK: - Ready content

/// Everything the Home screen renders when `HomeUiState.Ready` is active.
struct HomeReady: Equatable {
    let shelves: [ShelfItem]
    let isSyncing: Bool
    /// The library's first population or this device's first catch-up is running (spec H5).
    let isLibraryArriving: Bool
    /// "412 of 1,280 books", only while a scan knows both numbers (open question 1).
    let arrivalCount: ArrivalCount?
    /// The device or the server is out of reach (the shared rule, C15); independent of a failed row.
    let isOffline: Bool
    /// The library-arriving strip shows above the slot (the shared rule, spec H5).
    let showsArrivalStrip: Bool
    /// My Shelves shows its loading state, not the invitation, while the library arrives (the shared rule).
    let showsShelvesLoading: Bool
    let timeOfDay: HomeTimeOfDay
    let firstName: String?
    let slot: HomeSlotItem
    /// Every book in progress, the loaded one first (the shared rule): Everything in Progress.
    let everythingInProgress: [ContinueItem]
    let connectivity: HomeConnectivityState
    /// The Continue Listening failure's message while the rest of Home loaded, or nil.
    let continueListeningError: String?

    init(from ready: HomeUiStateReady) {
        self.shelves = ready.myShelves.map(ShelfItem.init(from:))
        self.isSyncing = ready.isSyncing
        self.isLibraryArriving = ready.isLibraryArriving
        self.arrivalCount = ready.scanProgress.flatMap { scan in
            scan.booksTotal > 0 ? ArrivalCount(done: Int(scan.books), total: Int(scan.booksTotal)) : nil
        }
        self.isOffline = ready.isOffline
        self.showsArrivalStrip = ready.showsArrivalStrip
        self.showsShelvesLoading = ready.showsShelvesLoading
        self.timeOfDay = HomeTimeOfDay(kmp: ready.timeOfDay)
        self.firstName = ready.firstName
        self.slot = HomeSlotItem(from: ready.slot)
        let connectivity = ready.connectivity
        self.everythingInProgress = ready.everythingInProgress.map {
            ContinueItem(from: $0, connectivity: connectivity)
        }
        self.connectivity = HomeConnectivityState(kmp: ready.connectivity)
        self.continueListeningError = ready.continueListeningError?.message
    }
}

/// How far a scan has got, when it knows both numbers.
struct ArrivalCount: Equatable {
    let done: Int
    let total: Int
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
    /// Out of reach and not downloaded: the card dims and reads "Needs a connection" (spec §2.5, the shared rule).
    var needsConnection = false

    /// A card online, as CarPlay and every other caller without a connectivity reads it.
    init(from item: ContinueListeningItem) {
        self.init(from: item, connectivity: HomeConnectivity.Online)
    }

    init(from item: ContinueListeningItem, connectivity: HomeConnectivity) {
        switch item.sealedType() {
        case .ready(let readyType):
            let book = readyType.value.book
            self.init(book: book)
            let rules = ExportedKotlinPackages.com.calypsan.listenup.client.presentation.home.self
            self.needsConnection = rules.needsConnection(book, connectivity: connectivity)
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
    case justFinished(FinishedSlot)
    case nothingInProgress(NextBookItem?)
    case newListener(covers: [DiscoverShelfCover])

    init(from slot: HomeSlot) {
        switch slot.sealedType() {
        case .absent:
            self = .absent
        case .firstSync:
            self = .firstSync
        case .justFinished(let finishedType):
            self = .justFinished(FinishedSlot(from: finishedType.value.card))
        case .nothingInProgress(let nothingType):
            self = .nothingInProgress(nothingType.value.candidate.map(NextBookItem.init(from:)))
        case .newListener(let newType):
            self = .newListener(covers: newType.value.covers.map(DiscoverShelfCover.init(from:)))
        }
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

/// The next book in a series — the card's Play next book, NothingInProgress's candidate.
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
