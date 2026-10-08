import SwiftUI
import Shared

/// Observes the shared `UserPermissionsViewModel`, flattening its state into a native model the SwiftUI
/// screen binds to. Thin over `FlowBridge`, like the other admin observers.
@Observable
@MainActor
final class UserPermissionsObserver {
    private(set) var phase: UserPermissionsPhase = .loading

    private let viewModel: UserPermissionsViewModel
    private let bridge = FlowBridge()

    init(viewModel: UserPermissionsViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }

    // MARK: - Actions

    func selectPreset(_ preset: PermissionPreset) { viewModel.selectPreset(preset: preset) }
    func setPermission(_ permission: Permission, granted: Bool) {
        viewModel.setPermission(permission: permission, granted: granted)
    }
    func requestRole(_ role: UserRole) { viewModel.requestRole(role: role) }
    func confirmAdminPromotion() { viewModel.confirmAdminPromotion() }
    func cancelAdminPromotion() { viewModel.cancelAdminPromotion() }
    func discard() { viewModel.discard() }
    func save() { viewModel.save() }

    // MARK: - State mapping

    private func apply(_ state: UserPermissionsUiState) {
        switch state.sealedType() {
        case .loading:
            phase = .loading
        case .ready(let readyType):
            phase = .ready(UserPermissionsReadyModel(from: readyType.value))
        case .error(let errType):
            phase = .error(errType.value.error.message)
        }
    }
}

/// Flattened permissions state for a SwiftUI `switch`.
enum UserPermissionsPhase {
    case loading
    case ready(UserPermissionsReadyModel)
    case error(String)
}

/// One toggle, natively.
struct PermissionRowModel: Identifiable {
    let permission: Permission
    let granted: Bool
    var id: String { "\(permission)" }
}

/// One group of toggles, natively.
struct PermissionSectionModel: Identifiable {
    let group: PermissionGroup
    let rows: [PermissionRowModel]
    var id: String { "\(group)" }
}

/// Native snapshot of the ready state: the drafted role and flags, the preset they read as, and the
/// save state.
struct UserPermissionsReadyModel {
    /// The presets an admin can pick, in menu order (Kotlin's `PermissionPreset.pickable`). Custom is
    /// arrived at, never picked. Spelled here because Swift Export traps casting a Kotlin list of enums.
    static var pickablePresets: [PermissionPreset] { [.listener, .contributor, .librarian] }

    let name: String
    let isOwner: Bool
    let role: UserRole
    let isAdmin: Bool
    let preset: PermissionPreset
    let presetsShown: Bool
    let sections: [PermissionSectionModel]
    let curateWarningShown: Bool
    let changeCount: Int
    let hasChanges: Bool
    let isProtected: Bool
    let isConfirmingAdminPromotion: Bool
    let isSaving: Bool
    let error: String?

    init(from ready: UserPermissionsUiStateReady) {
        self.name = ready.user.displayableName
        self.isOwner = ready.user.isRoot
        self.role = ready.role
        self.isAdmin = ready.isAdminRole
        self.preset = ready.preset
        self.presetsShown = ready.presetsShown
        self.sections = ready.sections.map { section in
            PermissionSectionModel(
                group: section.group,
                rows: section.rows.map { PermissionRowModel(permission: $0.permission, granted: $0.granted) }
            )
        }
        self.curateWarningShown = ready.curateWarningShown
        self.changeCount = Int(ready.changeCount)
        self.hasChanges = ready.hasChanges
        self.isProtected = ready.isProtected
        self.isConfirmingAdminPromotion = ready.isConfirmingAdminPromotion
        self.isSaving = ready.isSaving
        self.error = ready.error?.message
    }
}
