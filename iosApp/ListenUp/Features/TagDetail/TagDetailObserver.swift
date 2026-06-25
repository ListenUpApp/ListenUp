import SwiftUI
@preconcurrency import Shared

/// The render phase of the tag detail screen, flattened from `TagDetailUiState`.
///
/// `Idle` and `Loading` from the shared VM both collapse to `.loading` here — the
/// screen has nothing to show until the tag resolves, and the distinction isn't
/// user-visible.
enum TagDetailPhase: Equatable {
    case loading
    case ready
    case error(String)
}

/// A flat projection of `TagDetailUiState`, computed once so the mapping is pure
/// and unit-testable rather than buried in the observer's `apply`.
struct TagDetailSnapshot: Equatable {
    var phase: TagDetailPhase = .loading
    var tagName: String = ""
    var books: [BookRow] = []

    var bookCount: Int { books.count }

    /// The pluralized "N books" subtitle key for a given count. Pure so the
    /// singular/plural branch is unit-tested rather than buried in the view.
    static func bookCountKey(_ count: Int) -> String {
        count == 1 ? "tag.book_count" : "tag.books_count"
    }

    /// Flatten the sealed shared state into the flat snapshot the UI renders.
    static func from(_ state: TagDetailUiState) -> TagDetailSnapshot {
        switch onEnum(of: state) {
        case .idle, .loading:
            return TagDetailSnapshot(phase: .loading)
        case .ready(let r):
            return TagDetailSnapshot(
                phase: .ready,
                tagName: r.tagName,
                books: r.books.map { BookRow($0) }
            )
        case .error(let errorState):
            return TagDetailSnapshot(phase: .error(errorState.message), tagName: "")
        case .unknown:
            Log.error("Unexpected TagDetailUiState case")
            return TagDetailSnapshot(phase: .error(String(localized: "common.something_went_wrong")), tagName: "")
        }
    }
}

/// Observes `TagDetailViewModel`, flattening `TagDetailUiState` into flat
/// `@Observable` properties the SwiftUI screen binds to.
@Observable
@MainActor
final class TagDetailObserver {
    private(set) var phase: TagDetailPhase = .loading
    private(set) var tagName: String = ""
    private(set) var books: [BookRow] = []

    var bookCount: Int { books.count }

    private let viewModel: TagDetailViewModel
    private let bridge = FlowBridge()

    init(viewModel: TagDetailViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit {
        // Held in SwiftUI `@State` on a `@MainActor` view, so dealloc is main-thread.
        MainActor.assumeIsolated { bridge.cancelAll() }
    }

    func stopObserving() { bridge.cancelAll() }

    func loadTag(_ id: String) { viewModel.loadTag(tagId: id) }

    private func apply(_ state: TagDetailUiState) {
        let snapshot = TagDetailSnapshot.from(state)
        phase = snapshot.phase
        tagName = snapshot.tagName
        books = snapshot.books
    }
}
