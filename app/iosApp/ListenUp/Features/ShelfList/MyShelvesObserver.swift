import Foundation
import Shared

/// The My Shelves list's render phase, flattened from the shared `MyShelvesUiState` (spec §3.2 H24).
enum MyShelvesPhase: Equatable {
    case loading
    /// The reader's shelves, in Home's order; empty means none yet (the invitation).
    case ready([ShelfItem])
    case error

    init(from state: MyShelvesUiState) {
        switch state.sealedType() {
        case .loading:
            self = .loading
        case .ready(let readyType):
            self = .ready(readyType.value.shelves.map(ShelfItem.init(from:)))
        case .error:
            self = .error
        }
    }
}

/// Observes `MyShelvesViewModel` for the My Shelves list. The list has no actions of its own — opening a shelf and
/// starting a new one are navigation and a sheet.
@Observable
@MainActor
final class MyShelvesObserver {
    private(set) var phase: MyShelvesPhase = .loading

    private let viewModel: MyShelvesViewModel
    private let bridge = FlowBridge()

    init(viewModel: MyShelvesViewModel) {
        self.viewModel = viewModel
        bridge.own(viewModel)
        bridge.bind(viewModel.state) { [weak self] in self?.phase = MyShelvesPhase(from: $0) }
    }
}
