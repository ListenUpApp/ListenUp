import Foundation
@preconcurrency import Shared

/// Observes `DiscoverViewModel` — flattens its two iOS-relevant sealed states into
/// SwiftUI-native phases: `discoverBooksState` (the "New for You" rail) and
/// `recentlyAddedState` (the "Recently Added" list). The Android-only
/// `discoverShelvesState` / `currentlyListeningState` streams are intentionally ignored;
/// the iOS Discover design omits those sections.
///
/// Thin over `FlowBridge`; all mapping logic lives in pure, testable initializers.
@Observable
@MainActor
final class DiscoverObserver {
    // MARK: - State

    private(set) var newForYou: DiscoverBooksPhase = .loading
    private(set) var recentlyAdded: RecentlyAddedPhase = .loading

    // MARK: - Dependencies

    private let viewModel: DiscoverViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: DiscoverViewModel = Dependencies.shared.createDiscoverViewModel()) {
        self.viewModel = viewModel
        bridge.bind(viewModel.discoverBooksState) { [weak self] in self?.applyBooks($0) }
        bridge.bind(viewModel.recentlyAddedState) { [weak self] in self?.applyRecent($0) }
    }

    /// Stop observing. Call on teardown.
    func stopObserving() {
        bridge.cancelAll()
    }

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
        }
    }
}

// MARK: - Phases

/// Flattened "New for You" rail state for a SwiftUI `switch`.
enum DiscoverBooksPhase: Equatable {
    case loading
    case ready([DiscoverBook])
    case error
}

/// Flattened "Recently Added" list state for a SwiftUI `switch`.
enum RecentlyAddedPhase: Equatable {
    case loading
    case ready([RecentlyAddedBook])
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
