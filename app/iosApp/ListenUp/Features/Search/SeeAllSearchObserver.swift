import SwiftUI
import Shared

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

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    /// Load the full list of `type` hits for `query`. Idempotent for an unchanged request, so
    /// calling it from `.task` on every appearance is safe.
    func load(query: String, type: SearchSeeAllType) {
        viewModel.load(query: query, type: type.hitType)
    }

    func selectRow(_ row: SearchRow) {
        viewModel.onResultSelected(id: row.id, type: row.kind.hitType, name: row.name)
    }

    // MARK: - State mapping

    private func apply(_ state: SeeAllSearchUiState) {
        switch state.sealedType() {
        case .idle:
            phase = .idle
        case .loading:
            phase = .loading
        case .tooShort:
            // Must precede any empty-collapse: routing this through .empty (or, before this case
            // existed, through .unknown's error branch) tells the user their search failed when
            // they have simply not typed enough for the trigram index to answer yet.
            phase = .tooShort
        case .results(let resultsType):
            let results = resultsType.value
            phase = results.hits.isEmpty ? .empty : .results(results.hits.map { SearchRow($0) })
        case .error(let errorType):
            let error = errorType.value
            phase = .error(error.message)
        }
    }

    private func applyNav(_ action: SearchNavAction) {
        switch action.sealedType() {
        case .navigateToBook(let aType):
            let a = aType.value
            pendingNavigation = .book(id: a.bookId)
        case .navigateToContributor(let aType):
            let a = aType.value
            pendingNavigation = .contributor(id: a.contributorId)
        case .navigateToSeries(let aType):
            let a = aType.value
            pendingNavigation = .series(id: a.seriesId)
        case .navigateToTag(let aType):
            let a = aType.value
            pendingNavigation = .tag(id: a.tagId, name: a.tagName)
        }
    }
}

/// The render phase of the See-all page, flattened from the shared `SeeAllSearchUiState`.
enum SeeAllPhase: Equatable {
    case idle
    case loading
    case tooShort
    case results([SearchRow])
    case empty
    case error(String)
}
