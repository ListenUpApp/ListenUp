import SwiftUI
@preconcurrency import Shared

/// Observes `SeeAllSearchViewModel` — flattens the sealed `SeeAllSearchUiState` into a flat
/// `SeeAllPhase`, and drains tapped-hit `SearchNavAction`s into a one-shot `pendingNavigation`.
/// Thin over `FlowBridge`, mirroring `SearchObserver`.
@Observable
@MainActor
final class SeeAllSearchObserver {
    // MARK: - Flattened state

    private(set) var phase: SeeAllPhase = .idle

    /// One-shot navigation target produced by a tapped hit. The view consumes and clears it
    /// (`nil`) once the push is enqueued — same contract as `SearchObserver`.
    var pendingNavigation: SearchRoute?

    private let viewModel: SeeAllSearchViewModel
    private let bridge = FlowBridge()

    init(viewModel: SeeAllSearchViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.navActions) { [weak self] in self?.applyNav($0) }
    }

    deinit { MainActor.assumeIsolated { bridge.cancelAll() } }

    // MARK: - Actions

    /// Load the full list of `type` hits for `query`. Idempotent for an unchanged request, so
    /// calling it from `.task` on every appearance is safe.
    func load(query: String, type: SearchSeeAllType) {
        viewModel.load(query: query, type: type.hitType)
    }

    func selectRow(_ row: SearchRow) {
        viewModel.onResultSelected(id: row.id, type: row.kind.hitType)
    }

    // MARK: - State mapping

    private func apply(_ state: SeeAllSearchUiState) {
        switch onEnum(of: state) {
        case .idle:
            phase = .idle
        case .loading:
            phase = .loading
        case .results(let results):
            phase = results.hits.isEmpty ? .empty : .results(results.hits.map { SearchRow($0) })
        case .error(let error):
            phase = .error(error.message)
        }
    }

    private func applyNav(_ action: SearchNavAction) {
        switch onEnum(of: action) {
        case .navigateToBook(let a): pendingNavigation = .book(id: a.bookId)
        case .navigateToContributor(let a): pendingNavigation = .contributor(id: a.contributorId)
        case .navigateToSeries(let a): pendingNavigation = .series(id: a.seriesId)
        case .navigateToTag(let a): pendingNavigation = .tag(id: a.tagId)
        }
    }
}

/// The render phase of the See-all page, flattened from the shared `SeeAllSearchUiState`.
enum SeeAllPhase: Equatable {
    case idle
    case loading
    case results([SearchRow])
    case empty
    case error(String)
}
