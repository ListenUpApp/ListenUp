import Foundation
import Shared

/// Observes `AdminCategoriesViewModel` — flattens the sealed `AdminCategoriesUiState` into a
/// SwiftUI-native `AdminCategoriesPhase`, and turns the Kotlin genre tree into the flat list of
/// visible rows the screen renders. That flatten happens HERE, once per emission, so the `List`
/// diffs plain Swift values and never re-reads a bridged object (rule 8).
///
/// Every action forwards straight to the Kotlin VM; expansion state lives there too, so the
/// tree the admin sees is the same one Android and the web would show.
@Observable
@MainActor
final class AdminCategoriesObserver {
    // MARK: - State

    private(set) var phase: AdminCategoriesPhase = .loading

    // MARK: - Dependencies

    private let viewModel: AdminCategoriesViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: AdminCategoriesViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.phase = Self.phase(from: $0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func toggleExpanded(id: String) { viewModel.toggleExpanded(genreId: id) }
    func expandAll() { viewModel.expandAll() }
    func collapseAll() { viewModel.collapseAll() }
    func createGenre(name: String, parentId: String?) { viewModel.createGenre(name: name, parentId: parentId) }
    func renameGenre(id: String, name: String) { viewModel.renameGenre(id: id, name: name) }
    func deleteGenre(id: String) { viewModel.deleteGenre(id: id) }
    func moveGenre(id: String, newParentId: String?) { viewModel.moveGenre(id: id, newParentId: newParentId) }
    func mergeGenres(source: String, target: String) { viewModel.mergeGenres(source: source, target: target) }
    func clearError() { viewModel.clearError() }

    // MARK: - State mapping

    /// `nonisolated` so tests can exercise it off the main actor (mirrors the inbox observer).
    nonisolated static func phase(from state: AdminCategoriesUiState) -> AdminCategoriesPhase {
        switch state.sealedType() {
        case .loading:
            return .loading
        case .ready(let readyType):
            return .ready(AdminCategoriesReadyModel(from: readyType.value))
        case .error(let errorType):
            return .error(errorType.value.error.message)
        }
    }
}

// MARK: - Phase

/// Flattened categories state for a SwiftUI `switch`.
enum AdminCategoriesPhase {
    case loading
    case ready(AdminCategoriesReadyModel)
    case error(String)
}

// MARK: - Ready model

struct AdminCategoriesReadyModel {
    /// The rows currently visible, in display order, already indented by depth.
    let rows: [GenreRowModel]
    /// Every genre, flat — what the move and merge sheets pick from.
    let picks: [GenrePickModel]
    let isSaving: Bool
    let genreCount: Int
    let totalBookCount: Int
    /// Every node that can expand is expanded — flips the toolbar button to "Collapse All".
    let allExpanded: Bool
    let error: String?

    init(from ready: AdminCategoriesUiStateReady) {
        let roots = Array(ready.tree).map(GenreNodeModel.init(from:))
        let expanded = Set(Array(ready.expandedIds))
        let expandable = GenreTree.expandableIds(roots)
        self.rows = GenreTree.visibleRows(roots, expanded: expanded)
        self.picks = Array(ready.genres).map(GenrePickModel.init(from:))
        self.isSaving = ready.isSaving
        self.genreCount = self.picks.count
        self.totalBookCount = Int(ready.totalBookCount)
        self.allExpanded = !expandable.isEmpty && expandable.isSubset(of: expanded)
        self.error = ready.error?.message
    }
}
