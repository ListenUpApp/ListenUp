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

    // MARK: - Actions

    func toggleCanShare() { viewModel.toggleCanShare() }
    func clearError() { viewModel.clearError() }

    // MARK: - State mapping

    private func apply(_ state: UserDetailUiState) {
        switch onEnum(of: state) {
        case .loading:
            phase = .loading
        case .ready(let ready):
            phase = .ready(UserDetailReadyModel(from: ready))
        case .error(let err):
            phase = .error(err.error.message)
        case .unknown:
            Log.error("Unexpected UserDetailUiState case")
            phase = .error(String(localized: "common.something_went_wrong"))
        }
    }
}

/// Flattened user-detail state for a SwiftUI `switch`.
enum UserDetailPhase {
    case loading
    case ready(UserDetailReadyModel)
    case error(String)
}

/// Native snapshot of the ready state — the user's display fields plus the editable Can Share
/// permission and the `isProtected` guard that disables it for protected users.
struct UserDetailReadyModel {
    let displayName: String
    let email: String
    let role: String
    let canShare: Bool
    let isProtected: Bool
    let isSaving: Bool
    let error: String?

    init(from ready: UserDetailUiStateReady) {
        self.displayName = ready.user.displayName ?? ready.user.email
        self.email = ready.user.email
        self.role = ready.user.role
        self.canShare = ready.canShare
        self.isProtected = ready.isProtected
        self.isSaving = ready.isSaving
        self.error = ready.error?.message
    }
}
