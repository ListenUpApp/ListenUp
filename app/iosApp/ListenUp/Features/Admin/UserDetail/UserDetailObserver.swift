import SwiftUI
import Shared

/// Observes the shared `UserDetailViewModel`, flattening `UserDetailUiState` into native `@Observable`
/// properties the SwiftUI screen binds to. Mirrors the other admin observers (thin over `FlowBridge`).
@Observable
@MainActor
final class UserDetailObserver {
    private(set) var phase: UserDetailPhase = .loading

    private let viewModel: UserDetailViewModel
    private let bridge = FlowBridge()

    init(viewModel: UserDetailViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }

    // MARK: - State mapping

    private func apply(_ state: UserDetailUiState) {
        switch state.sealedType() {
        case .loading:
            phase = .loading
        case .ready(let readyType):
            let ready = readyType.value
            phase = .ready(UserDetailReadyModel(from: ready))
        case .error(let errType):
            let err = errType.value
            phase = .error(err.error.message)
        }
    }
}

/// Flattened user-detail state for a SwiftUI `switch`.
enum UserDetailPhase {
    case loading
    case ready(UserDetailReadyModel)
    case error(String)
}

/// Native snapshot of the ready state — the user's display fields, their access label, and whether they
/// are the protected owner. Their role and permissions are edited on the permissions screen.
struct UserDetailReadyModel {
    let userId: String
    let displayName: String
    let email: String
    let access: AccessLabel
    let isProtected: Bool

    init(from ready: UserDetailUiStateReady) {
        self.userId = ready.user.id
        self.displayName = ready.user.displayableName
        self.email = ready.user.email
        self.access = ready.user.access
        self.isProtected = ready.user.isProtected
    }
}
