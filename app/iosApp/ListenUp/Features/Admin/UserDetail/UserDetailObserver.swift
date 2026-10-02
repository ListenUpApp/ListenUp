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

    func toggle(_ permission: UserDetailPermission) {
        switch permission {
        case .canEdit: viewModel.toggleCanEdit()
        }
    }
    func clearError() { viewModel.clearError() }

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

/// Native snapshot of the ready state — the user's display fields plus the editable Can Edit
/// permission and the `isProtected` guard that disables it for protected users.
struct UserDetailReadyModel {
    let displayName: String
    let email: String
    let role: String
    let canEdit: Bool
    let isProtected: Bool
    let isSaving: Bool
    let error: String?

    init(from ready: UserDetailUiStateReady) {
        self.displayName = ready.user.displayName ?? ready.user.email
        self.email = ready.user.email
        self.role = ready.user.role
        self.canEdit = ready.canEdit
        self.isProtected = ready.isProtected
        self.isSaving = ready.isSaving
        self.error = ready.error?.message
    }
}

/// The permissions an admin can grant from a user's detail, in display order — the same set
/// Android's `UserDetailScreen` and the web `UserDetailPage` offer. "Can share" is not here: it gated
/// nothing once only admins could write collections, so it was removed on every platform.
enum UserDetailPermission: CaseIterable, Identifiable {
    case canEdit

    var id: Self { self }

    var title: String {
        switch self {
        case .canEdit: String(localized: "admin.can_edit")
        }
    }

    var detail: String {
        switch self {
        case .canEdit: String(localized: "admin.allow_editing_content_metadata")
        }
    }

    func isGranted(in ready: UserDetailReadyModel) -> Bool {
        switch self {
        case .canEdit: ready.canEdit
        }
    }
}
