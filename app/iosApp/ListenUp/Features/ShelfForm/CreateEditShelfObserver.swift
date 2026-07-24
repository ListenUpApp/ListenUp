import SwiftUI
import Shared

/// Flattened render phase for the Create/Edit Shelf screen.
enum CreateEditShelfPhase: Equatable {
    case idle
    case loadingExisting
    case loaded(name: String, description: String, isPrivate: Bool)
    case saving
    case error(String)
}

/// Observes `CreateEditShelfViewModel`, flattening its sealed state into
/// `@Observable` properties and forwarding nav actions to a dismiss callback.
@Observable
@MainActor
final class CreateEditShelfObserver {
    private(set) var phase: CreateEditShelfPhase = .idle
    var onClose: (() -> Void)?

    private let viewModel: CreateEditShelfViewModel
    private let bridge = FlowBridge()

    init(viewModel: CreateEditShelfViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.navActions) { [weak self] in self?.applyNav($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    func prepareCreate() { viewModel.initCreate() }
    func prepareEdit(shelfId: String) { viewModel.initEdit(shelfId: shelfId) }

    func save(name: String, description: String, isPrivate: Bool) {
        viewModel.save(name: name, description: description, isPrivate: isPrivate)
    }

    func delete() { viewModel.delete() }
    func dismissError() { viewModel.dismissError() }

    private func apply(_ state: CreateEditShelfUiState) {
        switch onEnum(of: state) {
        case .idle:
            phase = .idle
        case .loadingExisting:
            phase = .loadingExisting
        case .loaded(let state):
            phase = .loaded(name: state.name, description: state.description_, isPrivate: state.isPrivate)
        case .saving:
            phase = .saving
        case .error(let errorState):
            phase = .error(errorState.message)
        case .unknown:
            Log.error("Unexpected CreateEditShelfUiState case")
            phase = .error(String(localized: "common.something_went_wrong"))
        }
    }

    private func applyNav(_ action: CreateEditShelfNavAction) {
        switch onEnum(of: action) {
        case .navigateBack:
            onClose?()
        case .unknown:
            Log.error("Unexpected CreateEditShelfNavAction case")
        }
    }
}
