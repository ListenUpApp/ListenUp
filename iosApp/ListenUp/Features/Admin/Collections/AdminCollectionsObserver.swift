import Foundation
import Shared

/// Observes `AdminCollectionsViewModel` — flattens the sealed `AdminCollectionsUiState`
/// into a SwiftUI-native `AdminCollectionsPhase` and surfaces collection rows as pre-mapped
/// Swift models.
///
/// Actions (`createCollection`, `deleteCollection`, `clearError`, `clearCreateSuccess`)
/// forward directly to the Kotlin VM. The observer never owns business logic.
@Observable
@MainActor
final class AdminCollectionsObserver {
    // MARK: - State

    private(set) var phase: AdminCollectionsPhase = .loading

    // MARK: - Dependencies

    private let viewModel: AdminCollectionsViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: AdminCollectionsViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func createCollection(name: String) { viewModel.createCollection(name: name) }
    func deleteCollection(collectionId: String) { viewModel.deleteCollection(collectionId: collectionId) }
    func clearError() { viewModel.clearError() }
    func clearCreateSuccess() { viewModel.clearCreateSuccess() }

    // MARK: - State mapping

    private func apply(_ state: AdminCollectionsUiState) {
        switch onEnum(of: state) {
        case .loading:
            phase = .loading
        case .ready(let ready):
            phase = .ready(AdminCollectionsReadyModel(from: ready))
        case .error(let err):
            phase = .error(err.message)
        case .unknown:
            Log.error("Unexpected AdminCollectionsUiState case")
            phase = .error(String(localized: "common.something_went_wrong"))
        }
    }
}

// MARK: - Phase

/// Flattened collections list state for a SwiftUI `switch`.
enum AdminCollectionsPhase {
    case loading
    case ready(AdminCollectionsReadyModel)
    case error(String)
}

// MARK: - Ready model

struct AdminCollectionsReadyModel {
    let collections: [CollectionRowModel]
    let isCreating: Bool
    let createSuccess: Bool
    let deletingCollectionId: String?
    let error: String?

    init(from ready: AdminCollectionsUiStateReady) {
        self.collections = Array(ready.collections).map(CollectionRowModel.init(from:))
        self.isCreating = ready.isCreating
        self.createSuccess = ready.createSuccess
        self.deletingCollectionId = ready.deletingCollectionId
        self.error = ready.error
    }
}

// MARK: - Row model

struct CollectionRowModel: Identifiable {
    let id: String
    let name: String
    let bookCount: Int

    init(from collection: Collection) {
        self.id = collection.id
        self.name = collection.name
        self.bookCount = Int(collection.bookCount)
    }
}
