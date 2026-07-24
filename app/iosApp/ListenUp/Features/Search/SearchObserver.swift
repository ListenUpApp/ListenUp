import SwiftUI
import Shared

/// Observes `SearchViewModel` — flattens the sealed `SearchUiState` into flat
/// `@Observable` properties and projects the additive `selectedTypes` filter onto a
/// single-select `SearchScope`. Thin over `FlowBridge`, mirroring `LibraryObserver`.
@Observable
@MainActor
final class SearchObserver {
    // MARK: - Flattened state

    private(set) var query: String = ""
    private(set) var selectedScope: SearchScope = .all
    private(set) var phase: SearchPhase = .idle
    private(set) var groups = SearchHitGroups()

    /// One-shot navigation target produced by a tapped hit. The view consumes and
    /// clears it (`nil`) once the push is enqueued.
    var pendingNavigation: SearchRoute?

    /// The VM's current filter set — the source of truth for translating a scope
    /// selection into the right sequence of additive `toggleTypeFilter` calls.
    private var selectedTypes: Set<SearchHitType> = []

    private let viewModel: SearchViewModel
    private let bridge = FlowBridge()

    init(viewModel: SearchViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.navActions) { [weak self] in self?.applyNav($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func setQuery(_ value: String) {
        viewModel.onQueryChanged(query: value)
    }

    func clearQuery() {
        viewModel.clearQuery()
    }

    /// Move the VM's additive filter set to exactly this scope. The VM only exposes a
    /// toggle, so we fire one toggle per differing type (the symmetric difference).
    func selectScope(_ scope: SearchScope) {
        for type in scope.toggles(from: selectedTypes) {
            viewModel.toggleTypeFilter(type: type)
        }
    }

    /// A row was tapped — let the VM emit the matching `SearchNavAction` (from id + kind + name),
    /// which we drain back into `pendingNavigation`. The native `SearchRow` carries no Kotlin
    /// object, so nav goes through the VM by id + type + name rather than the whole hit.
    func selectRow(_ row: SearchRow) {
        viewModel.onResultSelected(id: row.id, type: row.kind.hitType, name: row.name)
    }

    // MARK: - State mapping

    private func apply(_ state: SearchUiState) {
        query = state.query
        selectedTypes = state.selectedTypes
        selectedScope = SearchScope.from(selectedTypes: state.selectedTypes)

        switch onEnum(of: state) {
        case .idle:
            phase = .idle
            groups = SearchHitGroups()
        case .searching:
            phase = .searching
        case .results(let results):
            groups = SearchHitGroups.group(results.result.hits.map { SearchRow($0) })
            phase = groups.isEmpty ? .empty : .results
        case .error(let error):
            phase = .error(error.message)
            groups = SearchHitGroups()
        case .unknown:
            Log.error("Unexpected SearchUiState case")
            phase = .error(String(localized: "common.error"))
            groups = SearchHitGroups()
        }
    }

    private func applyNav(_ action: SearchNavAction) {
        switch onEnum(of: action) {
        case .navigateToBook(let a): pendingNavigation = .book(id: a.bookId)
        case .navigateToContributor(let a): pendingNavigation = .contributor(id: a.contributorId)
        case .navigateToSeries(let a): pendingNavigation = .series(id: a.seriesId)
        case .navigateToTag(let a): pendingNavigation = .tag(id: a.tagId, name: a.tagName)
        case .unknown: Log.error("Unexpected SearchNavAction case")
        }
    }
}
