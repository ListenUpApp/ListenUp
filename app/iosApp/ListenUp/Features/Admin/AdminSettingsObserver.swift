import Foundation
import Shared

/// Observes `AdminSettingsViewModel` — flattens the sealed `AdminSettingsUiState` into a
/// SwiftUI-native `AdminSettingsPhase` for the SERVER section of the admin screen.
///
/// Server name and remote URL are edit-buffer fields the user mutates locally; `isDirty`
/// gates the Save affordance and `isSaving` shows progress. A failure surfaces the typed
/// `AppError`'s message — iOS maps the typed error to inline state, never the Compose
/// errorBus (iosApp rule 10).
///
/// Thin over `FlowBridge`, mirroring `SettingsObserver`.
@Observable
@MainActor
final class AdminSettingsObserver {
    // MARK: - State

    private(set) var phase: AdminSettingsPhase = .loading

    // MARK: - Dependencies

    private let viewModel: AdminSettingsViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: AdminSettingsViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func reload() { viewModel.loadSettings() }
    func setServerName(_ name: String) { viewModel.setServerName(name: name) }
    func setRemoteUrl(_ url: String) { viewModel.setRemoteUrl(url: url) }
    func setInboxEnabled(_ enabled: Bool) { viewModel.setInboxEnabled(enabled: enabled) }
    func save() { viewModel.saveAll() }
    func clearError() { viewModel.clearError() }

    // MARK: - State mapping

    private func apply(_ state: AdminSettingsUiState) {
        switch onEnum(of: state) {
        case .loading:
            phase = .loading
        case .ready(let ready):
            phase = .ready(AdminSettingsReadyModel.from(ready))
        case .error(let error):
            phase = .error(message: error.error.message)
        case .unknown:
            Log.error("Unexpected AdminSettingsUiState case")
            phase = .error(message: String(localized: "common.something_went_wrong"))
        }
    }
}

// MARK: - Phase

/// Flattened server-settings state for a SwiftUI `switch`.
enum AdminSettingsPhase: Equatable {
    case loading
    case ready(AdminSettingsReadyModel)
    case error(message: String)
}

/// The flattened SERVER edit-buffer the screen binds to.
struct AdminSettingsReadyModel: Equatable {
    let serverName: String
    let remoteUrl: String
    let inboxEnabled: Bool
    let isDirty: Bool
    let isSaving: Bool
    /// Transient save/load failure message (nil when none), surfaced as an inline banner.
    let error: String?

    /// Pure mapping from the Swift Export-bridged KMP `Ready` state. `nonisolated` so tests can
    /// exercise it without a live observer or main-actor context.
    nonisolated static func from(_ ready: AdminSettingsUiStateReady) -> AdminSettingsReadyModel {
        AdminSettingsReadyModel(
            serverName: ready.serverName,
            remoteUrl: ready.remoteUrl,
            inboxEnabled: ready.inboxEnabled,
            isDirty: ready.isDirty,
            isSaving: ready.isSaving,
            error: ready.error?.message
        )
    }
}
