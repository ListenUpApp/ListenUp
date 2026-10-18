import Foundation
import Shared

/// Observes `DiscoverViewModel` — flattens its sealed states into SwiftUI-native phases:
/// `newForYou` (Something New), `recentlyAdded` (Recently Added), `currentlyListening` (Listening
/// Now, from `SocialService.currentlyListening`) with its `listeningSummary`, `shelves` (Shelves by
/// Others, drawn by `ShelvesByOthersSection`), `communitySize` (who else is on the server, for
/// the only-you treatments) and `viewer` (who is looking: the invitation's avatar and whether Invite
/// Someone is theirs to offer). Each section's Try Again restarts that section only.
///
/// Thin over `FlowBridge`; all mapping logic lives in pure, testable initializers/helpers.
@Observable
@MainActor
final class DiscoverObserver {
    // MARK: - State

    private(set) var newForYou: DiscoverBooksPhase = .loading
    private(set) var recentlyAdded: RecentlyAddedPhase = .loading
    private(set) var currentlyListening: CurrentlyListeningPhase = .loading
    private(set) var shelves: DiscoverShelvesPhase = .loading
    private(set) var communitySize: CommunityPhase = .unknown
    /// Who is looking; nil while signed out.
    private(set) var viewer: DiscoverViewerRow?
    /// Live/recent split, staleness and snapshot time of Listening now; `.empty` until it is Ready.
    private(set) var listeningSummary: ListeningSummary = .empty

    // MARK: - Dependencies

    private let viewModel: DiscoverViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: DiscoverViewModel = Dependencies.shared.createDiscoverViewModel()) {
        self.viewModel = viewModel
        bridge.own(viewModel)
        bridge.bind(viewModel.discoverBooksState) { [weak self] in self?.applyBooks($0) }
        bridge.bind(viewModel.recentlyAddedState) { [weak self] in self?.applyRecent($0) }
        bridge.bind(viewModel.currentlyListeningState) { [weak self] in self?.applyCurrentlyListening($0) }
        bridge.bind(viewModel.discoverShelvesState) { [weak self] in self?.applyShelves($0) }
        bridge.bind(viewModel.communitySize) { [weak self] in self?.applyCommunity($0) }
        bridge.bind(viewModel.viewer) { [weak self] in self?.viewer = $0.map(DiscoverViewerRow.init(from:)) }
    }

    // MARK: - Actions

    func refresh() {
        viewModel.refresh()
    }

    /// Draws ten different books for Something New; leaves every other section alone. Also Something
    /// New's Try Again: a fresh draw is its restart.
    func shuffle() {
        viewModel.shuffle()
    }

    /// Try Again on a failed Listening Now: restarts that section only.
    func retryListeningNow() {
        viewModel.retryListeningNow()
    }

    /// Try Again on a failed Recently Added: restarts that section only.
    func retryRecentlyAdded() {
        viewModel.retryRecentlyAdded()
    }

    /// Try Again on Shelves by Others: refetches the shelves, nothing else.
    func retryShelvesByOthers() {
        viewModel.retryShelvesByOthers()
    }

    // MARK: - State mapping

    private func applyBooks(_ state: DiscoverBooksUiState) {
        switch state.sealedType() {
        case .loading:
            newForYou = .loading
        case .ready(let readyType):
            let ready = readyType.value
            newForYou = .ready(ready.books.map(DiscoverBook.init(from:)))
        case .allStarted:
            newForYou = .allStarted
        case .error:
            newForYou = .error
        }
    }

    private func applyRecent(_ state: RecentlyAddedUiState) {
        switch state.sealedType() {
        case .loading:
            recentlyAdded = .loading
        case .ready(let readyType):
            let ready = readyType.value
            recentlyAdded = .ready(ready.books.map(RecentlyAddedBook.init(from:)))
        case .error:
            recentlyAdded = .error
        }
    }

    private func applyCurrentlyListening(_ state: CurrentlyListeningUiState) {
        switch state.sealedType() {
        case .loading:
            currentlyListening = .loading
        case .ready(let readyType):
            let ready = readyType.value
            currentlyListening = .ready(Self.currentlyListeningRows(from: ready.sessions))
            listeningSummary = ListeningSummary(
                liveCount: Int(ready.liveCount),
                recentCount: Int(ready.recentCount),
                isStale: ready.isStale,
                observedAtMs: ready.observedAtMs
            )
        case .error:
            currentlyListening = .error
        }
    }

    private func applyShelves(_ state: DiscoverShelvesUiState) {
        switch state.sealedType() {
        case .loading:
            shelves = .loading
        case .ready(let readyType):
            shelves = .ready(readyType.value.users.map(DiscoverShelfOwnerRow.init(from:)))
        case .error(let errorType):
            // "Need a connection" only when the failure is connectivity, decided once in shared code.
            shelves = .error(needsConnection: errorType.value.needsConnection)
        }
    }

    private func applyCommunity(_ size: CommunitySize) {
        switch size.sealedType() {
        case .unknown: communitySize = .unknown
        case .justYou: communitySize = .justYou
        case .others: communitySize = .others
        }
    }

    /// Pure: collapse the session list to one row per user (their most-recent book by
    /// `lastActiveAt`), then order live listeners ahead of the recent-listen fill, each half
    /// newest-first. The server already returns that order; re-deriving it here keeps the
    /// mapping total, so a future caller cannot get a silently reshuffled carousel.
    ///
    /// Liveness outranks recency deliberately: a non-live row's `lastActiveAt` is a
    /// `lastPlayedAt` that can easily be more recent than a live session's start, and sorting on
    /// the raw timestamp alone would interleave "listening now" with "two days ago". Mapping to a
    /// native value type here keeps bridged Kotlin sessions off the `ForEach` diff path (perf
    /// rule 8). `nonisolated` — testable off-actor.
    nonisolated static func currentlyListeningRows(
        from sessions: [CurrentlyListeningUiSession]
    ) -> [CurrentlyListeningRow] {
        var latestByUser: [String: CurrentlyListeningUiSession] = [:]
        for session in sessions {
            if let existing = latestByUser[session.userId], rank(existing) >= rank(session) {
                continue
            }
            latestByUser[session.userId] = session
        }
        return latestByUser.values
            .sorted { rank($0) > rank($1) }
            .map(CurrentlyListeningRow.init(from:))
    }

    /// Sort key: live first, then most-recently-active. `Bool` is not `Comparable` in Swift, so
    /// liveness is projected to an `Int` for the tuple comparison.
    private nonisolated static func rank(
        _ session: CurrentlyListeningUiSession
    ) -> (Int, Int64) {
        (session.isLive ? 1 : 0, session.lastActiveAt)
    }
}

// MARK: - Phases

/// Flattened "New for You" rail state for a SwiftUI `switch`.
enum DiscoverBooksPhase: Equatable {
    case loading
    case ready([DiscoverBook])
    /// Every opener and standalone is started, in a library that has books.
    case allStarted
    case error
}

/// Flattened "Recently Added" rail state for a SwiftUI `switch`.
enum RecentlyAddedPhase: Equatable {
    case loading
    case ready([RecentlyAddedBook])
    case error
}

/// Flattened "What Others Are Listening To" rail state for a SwiftUI `switch`.
enum CurrentlyListeningPhase: Equatable {
    case loading
    case ready([CurrentlyListeningRow])
    case error
}

/// Flattened Shelves by Others state.
enum DiscoverShelvesPhase: Equatable {
    case loading
    case ready([DiscoverShelfOwnerRow])
    /// `needsConnection` is true for an offline or timed-out failure (plan Q3).
    case error(needsConnection: Bool)
}

/// The reader looking at Discover, as a native value: the invitation's avatar and whether Invite
/// Someone is theirs to offer (only admins invite).
struct DiscoverViewerRow: Equatable {
    let userId: String
    let displayName: String
    let canInvite: Bool

    init(from viewer: DiscoverViewer) {
        self.userId = viewer.userId
        self.displayName = viewer.displayName
        self.canInvite = viewer.canInvite
    }

    init(userId: String, displayName: String, canInvite: Bool) {
        self.userId = userId
        self.displayName = displayName
        self.canInvite = canInvite
    }
}

/// Who else is on the server, for the only-you treatments.
enum CommunityPhase: Equatable {
    case unknown
    case justYou
    case others
}

/// The Listening Now heading's facts: "2 listening now · 3 more", and "As of …" when stale.
struct ListeningSummary: Equatable {
    let liveCount: Int
    let recentCount: Int
    let isStale: Bool
    let observedAt: Date?

    static let empty = ListeningSummary(liveCount: 0, recentCount: 0, isStale: false, observedAtMs: nil)

    init(liveCount: Int, recentCount: Int, isStale: Bool, observedAtMs: Int64?) {
        self.liveCount = liveCount
        self.recentCount = recentCount
        self.isStale = isStale
        self.observedAt = observedAtMs.map { Date(timeIntervalSince1970: TimeInterval($0) / 1_000) }
    }
}

/// One owner in Shelves by Others, with their shelves.
struct DiscoverShelfOwnerRow: Identifiable, Equatable {
    let id: String
    let displayName: String
    let shelves: [DiscoverShelfRow]

    init(from owner: DiscoverUserShelves) {
        self.id = owner.user.id
        self.displayName = owner.user.displayName
        self.shelves = owner.shelves.map(DiscoverShelfRow.init(from:))
    }
}

/// One of another reader's shelves: its name, count and up to four cover tiles.
struct DiscoverShelfRow: Identifiable, Equatable {
    let id: String
    let name: String
    let bookCount: Int
    let covers: [DiscoverShelfCover]

    init(from shelf: DiscoverShelfUi) {
        self.id = shelf.id
        self.name = shelf.name
        self.bookCount = Int(shelf.bookCount)
        self.covers = shelf.covers.map(DiscoverShelfCover.init(from:))
    }
}

/// One mosaic tile: a member book's cover, in shelf order.
struct DiscoverShelfCover: Equatable {
    let bookId: String
    let coverPath: String?
    let coverHash: String?

    init(from cover: ShelfCover) {
        self.bookId = cover.bookId
        self.coverPath = cover.coverPath
        self.coverHash = cover.coverHash
    }

    init(bookId: String, coverPath: String?, coverHash: String?) {
        self.bookId = bookId
        self.coverPath = coverPath
        self.coverHash = coverHash
    }
}

// MARK: - Item models

/// One cover card in the "New for You" rail.
struct DiscoverBook: Identifiable, Equatable {
    let id: String
    let title: String
    let author: String?
    let coverPath: String?
    let coverHash: String?
    /// The series this book starts; nil when standalone.
    let seriesName: String?
    /// Its place in that series ("1"); nil when standalone or unnumbered.
    let sequenceLabel: String?
    let durationMs: Int64

    init(from book: DiscoverUiBook) {
        self.id = book.id
        self.title = book.title
        self.author = book.authorName
        self.coverPath = book.coverPath
        self.coverHash = book.coverHash
        self.seriesName = book.seriesName
        self.sequenceLabel = book.sequenceLabel
        self.durationMs = book.durationMs
    }

    init(
        id: String,
        title: String,
        author: String?,
        coverPath: String?,
        coverHash: String?,
        seriesName: String? = nil,
        sequenceLabel: String? = nil,
        durationMs: Int64 = 0
    ) {
        self.id = id
        self.title = title
        self.author = author
        self.coverPath = coverPath
        self.coverHash = coverHash
        self.seriesName = seriesName
        self.sequenceLabel = sequenceLabel
        self.durationMs = durationMs
    }
}

/// One row in the "Recently Added" list. `addedAt` is the wall-clock instant the book
/// landed in the library — rendered with a native relative formatter ("2d ago").
struct RecentlyAddedBook: Identifiable, Equatable {
    let id: String
    let title: String
    let author: String?
    let coverPath: String?
    let coverHash: String?
    let addedAt: Date

    init(from book: RecentlyAddedUiBook) {
        self.id = book.id
        self.title = book.title
        self.author = book.authorName
        self.coverPath = book.coverPath
        self.coverHash = book.coverHash
        self.addedAt = Date(timeIntervalSince1970: TimeInterval(book.createdAt) / 1_000)
    }

    init(id: String, title: String, author: String?, coverPath: String?, coverHash: String?, addedAt: Date) {
        self.id = id
        self.title = title
        self.author = author
        self.coverPath = coverPath
        self.coverHash = coverHash
        self.addedAt = addedAt
    }
}

/// One carousel item in the "What Others Are Listening To" rail: a person and the single
/// book they're currently on. Tapping the card navigates to that book's detail. The avatar
/// is a neutral initials chip tinted with the user's stored avatar color; the cover comes
/// from the viewer's local library (the session is dropped upstream if the book isn't local).
struct CurrentlyListeningRow: Identifiable, Equatable {
    /// Stable per (user, book) — a user appears at most once after dedup, but keying on both
    /// keeps the identity stable across a book change so the card animates rather than replaces.
    let id: String
    let userId: String
    let displayName: String
    let initials: String
    let bookId: String
    let title: String
    let author: String?
    let coverPath: String?
    let coverHash: String?
    /// True when they are listening right now.
    let isLive: Bool
    /// When they were last active on this book.
    let lastActiveAt: Date

    init(from session: CurrentlyListeningUiSession) {
        self.id = "\(session.userId):\(session.bookId)"
        self.userId = session.userId
        self.displayName = session.displayName
        self.initials = LeaderboardRow.initials(from: session.displayName)
        self.bookId = session.bookId
        self.title = session.bookTitle
        self.author = session.authorName
        self.coverPath = session.coverPath
        self.coverHash = session.coverHash
        self.isLive = session.isLive
        self.lastActiveAt = Date(timeIntervalSince1970: TimeInterval(session.lastActiveAt) / 1_000)
    }

    init(
        id: String,
        userId: String,
        displayName: String,
        initials: String,
        bookId: String,
        title: String,
        author: String?,
        coverPath: String?,
        coverHash: String?,
        isLive: Bool = false,
        lastActiveAt: Date = .distantPast
    ) {
        self.id = id
        self.userId = userId
        self.displayName = displayName
        self.initials = initials
        self.bookId = bookId
        self.title = title
        self.author = author
        self.coverPath = coverPath
        self.coverHash = coverHash
        self.isLive = isLive
        self.lastActiveAt = lastActiveAt
    }
}
