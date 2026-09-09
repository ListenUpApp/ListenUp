import SwiftUI
import Shared

/// Observes `ClaimInviteViewModel`'s `state` flow, flattening `ClaimInviteUiState` into
/// SwiftUI-native properties. Thin over `FlowBridge`.
@Observable
@MainActor
final class ClaimInviteViewModelWrapper {
    enum Phase: Equatable {
        case codeEntry
        case confirmServer(host: String, signedInElsewhere: Bool)
        case lookingUp
        case preview
        case submitting
        case claimed
        case error(String)
    }

    private(set) var phase: Phase = .codeEntry
    private(set) var preview: InvitePreview?

    private let viewModel: ClaimInviteViewModel
    private let bridge = FlowBridge()

    init(viewModel: ClaimInviteViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    // Isolated deinit (SE-0371): runs hopped onto the main actor, so the non-Sendable Kotlin
    // viewModel can be closed here. No ViewModelStore on iOS calls onCleared, so this wrapper must
    // (#1192) — else the VM's stream/poll jobs orphan and run forever.
    isolated deinit {
        bridge.cancelAll()   // cancelAll() is nonisolated-safe; see FlowBridge.
        viewModel.close()
    }

    func lookUp(code: String) {
        viewModel.onCodeEntered(code: code.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    func start(serverURL: String?, code: String, remoteURL: String?) {
        viewModel.start(serverUrl: serverURL, code: code, remoteUrl: remoteURL)
    }

    func claim(password: String, firstName: String, lastName: String) {
        viewModel.onClaimSubmit(password: password, firstName: firstName, lastName: lastName)
    }

    /// The user agreed to point this device at the link's server.
    func confirmServer() {
        viewModel.onConfirmServer()
    }

    /// The user declined the link's server; the flow falls back to manual code entry.
    func cancelServer() {
        viewModel.onCancelServer()
    }

    // MARK: - State mapping

    private func apply(_ state: ClaimInviteUiState) {
        switch onEnum(of: state) {
        case .idle:
            phase = .codeEntry
            preview = nil
        case .confirmServer(let confirm):
            phase = .confirmServer(host: confirm.host, signedInElsewhere: confirm.signedInElsewhere)
        case .lookingUp:
            phase = .lookingUp
        case .preview(let previewState):
            preview = previewState.preview
            if previewState.preview.valid {
                phase = .preview
            } else {
                phase = .error(previewState.preview.invalidReason ?? String(localized: "invite.error_invalid"))
            }
        case .submitting:
            phase = .submitting
        case .claimed:
            phase = .claimed
        case .error(let errorState):
            phase = .error(errorState.message)
        case .unknown:
            Log.error("Unexpected ClaimInviteUiState case")
            phase = .error(String(localized: "invite.error_invalid"))
        }
    }
}
