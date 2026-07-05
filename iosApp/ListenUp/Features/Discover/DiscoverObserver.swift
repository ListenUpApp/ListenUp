import Foundation
import Shared

/// Observes `DiscoverViewModel` — flattens its three iOS-relevant sealed states into
/// SwiftUI-native phases: `newForYou` (the "New for You" rail), `recentlyAdded`
/// (the "Recently Added" rail), and `currentlyListening` (the "What Others Are Listening To"
/// rail, from `SocialService.currentlyListening`). The Android-only `discoverShelvesState`
/// stream is intentionally ignored; the iOS Discover design omits that section.
///
/// Thin over `FlowBridge`; all mapping logic lives in pure, testable initializers/helpers.
@Observable
@MainActor
final class DiscoverObserver {
    // MARK: - State

    private(set) var newForYou: DiscoverBooksPhase = .loading
    private(set) var recentlyAdded: RecentlyAddedPhase = .loading
    private(set) var currentlyListening: CurrentlyListeningPhase = .loading

    // MARK: - Dependencies

    private let viewModel: DiscoverViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: DiscoverViewModel = Dependencies.shared.createDiscoverViewModel()) {
        self.viewModel = viewModel
        bridge.bind(viewModel.discoverBooksState) { [weak self] in self?.applyBooks($0) }
        bridge.bind(viewModel.recentlyAddedState) { [weak self] in self?.applyRecent($0) }
        bridge.bind(viewModel.currentlyListeningState) { [weak self] in self?.applyCurrentlyListening($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func refresh() {
        viewModel.refresh()
    }

    // MARK: - State mapping

    private func applyBooks(_ state: DiscoverBooksUiState) {
        switch onEnum(of: state) {
        case .loading:
            newForYou = .loading
        case .ready(let ready):
            newForYou = .ready(ready.books.map(DiscoverBook.init(from:)))
        case .error:
            newForYou = .error
        case .unknown:
            Log.error("Unexpected DiscoverBooksUiState case")
            newForYou = .error
        }
    }

    private func applyRecent(_ state: RecentlyAddedUiState) {
        switch onEnum(of: state) {
        case .loading:
            recentlyAdded = .loading
        case .ready(let ready):
            recentlyAdded = .ready(ready.books.map(RecentlyAddedBook.init(from:)))
        case .error:
            recentlyAdded = .error
        case .unknown:
            Log.error("Unexpected RecentlyAddedUiState case")
            recentlyAdded = .error
        }
    }

    private func applyCurrentlyListening(_ state: CurrentlyListeningUiState) {
        switch onEnum(of: state) {
        case .loading:
            currentlyListening = .loading
        case .ready(let ready):
            currentlyListening = .ready(Self.currentlyListeningRows(from: ready.sessions))
        case .error:
            currentlyListening = .error
        case .unknown:
            Log.error("Unexpected CurrentlyListeningUiState case")
            currentlyListening = .error
        }
    }

    /// Pure: collapse the session list to one row per user (their most-recent book by
    /// `startedAt`), then sort the survivors most-recent-first so the freshest activity leads
    /// the carousel. The server's `currentlyListening` can return more than one session for a
    /// user (different books in flight); the design shows each person once, on the book they
    /// most recently picked up. Mapping to a native value type here keeps bridged Kotlin
    /// sessions off the `ForEach` diff path (perf rule 8). `nonisolated` — testable off-actor.
    nonisolated static func currentlyListeningRows(
        from sessions: [CurrentlyListeningUiSession]
    ) -> [CurrentlyListeningRow] {
        var latestByUser: [String: CurrentlyListeningUiSession] = [:]
        for session in sessions {
            if let existing = latestByUser[session.userId], existing.startedAt >= session.startedAt {
                continue
            }
            latestByUser[session.userId] = session
        }
        return latestByUser.values
            .sorted { $0.startedAt > $1.startedAt }
            .map(CurrentlyListeningRow.init(from:))
    }
}

// MARK: - Phases

/// Flattened "New for You" rail state for a SwiftUI `switch`.
enum DiscoverBooksPhase: Equatable {
    case loading
    case ready([DiscoverBook])
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

// MARK: - Item models

/// One cover card in the "New for You" rail.
struct DiscoverBook: Identifiable, Equatable {
    let id: String
    let title: String
    let author: String?
    let coverPath: String?
    let blurHash: String?

    init(from book: DiscoverUiBook) {
        self.id = book.id
        self.title = book.title
        self.author = book.authorName
        self.coverPath = book.coverPath
        self.blurHash = book.coverBlurHash
    }

    init(id: String, title: String, author: String?, coverPath: String?, blurHash: String?) {
        self.id = id
        self.title = title
        self.author = author
        self.coverPath = coverPath
        self.blurHash = blurHash
    }
}

/// One row in the "Recently Added" list. `addedAt` is the wall-clock instant the book
/// landed in the library — rendered with a native relative formatter ("2d ago").
struct RecentlyAddedBook: Identifiable, Equatable {
    let id: String
    let title: String
    let author: String?
    let coverPath: String?
    let blurHash: String?
    let addedAt: Date

    init(from book: RecentlyAddedUiBook) {
        self.id = book.id
        self.title = book.title
        self.author = book.authorName
        self.coverPath = book.coverPath
        self.blurHash = book.coverBlurHash
        self.addedAt = Date(timeIntervalSince1970: TimeInterval(book.createdAt) / 1_000)
    }

    init(id: String, title: String, author: String?, coverPath: String?, blurHash: String?, addedAt: Date) {
        self.id = id
        self.title = title
        self.author = author
        self.coverPath = coverPath
        self.blurHash = blurHash
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
    let blurHash: String?

    init(from session: CurrentlyListeningUiSession) {
        self.id = "\(session.userId):\(session.bookId)"
        self.userId = session.userId
        self.displayName = session.displayName
        self.initials = LeaderboardRow.initials(from: session.displayName)
        self.bookId = session.bookId
        self.title = session.bookTitle
        self.author = session.authorName
        self.coverPath = session.coverPath
        self.blurHash = session.coverBlurHash
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
        blurHash: String?
    ) {
        self.id = id
        self.userId = userId
        self.displayName = displayName
        self.initials = initials
        self.bookId = bookId
        self.title = title
        self.author = author
        self.coverPath = coverPath
        self.blurHash = blurHash
    }
}
