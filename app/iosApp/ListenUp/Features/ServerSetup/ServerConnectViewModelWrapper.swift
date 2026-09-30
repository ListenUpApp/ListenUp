import Foundation
import Shared

/// What the user can do, from inside the app, about a connect failure.
enum ConnectRecovery: Equatable {
    /// Local Network access is off for ListenUp; only its page in Settings can turn it back on.
    case openSettings
}

/// Observes `ServerConnectViewModel`'s `state` flow, flattening the sealed
/// `ServerConnectUiState` into SwiftUI-native properties. Holds the URL text as
/// wrapper input state (it is view input, not ViewModel state). Thin over `FlowBridge`.
@Observable
@MainActor
final class ServerConnectViewModelWrapper {
    /// The URL the user is typing — wrapper-held input state.
    var serverUrl: String = ""

    private(set) var isLoading: Bool = false
    private(set) var isVerified: Bool = false
    private(set) var error: String?
    /// The action that can fix the current failure, when there is one.
    private(set) var recovery: ConnectRecovery?

    /// Whether the Connect action should be enabled.
    var isConnectEnabled: Bool {
        !serverUrl.trimmingCharacters(in: .whitespaces).isEmpty && !isLoading
    }

    private let viewModel: ServerConnectViewModel
    private let bridge = FlowBridge()

    init(viewModel: ServerConnectViewModel) {
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

    // MARK: - Actions

    func onUrlChanged(_ url: String) {
        serverUrl = url
    }

    func onConnectClicked() {
        viewModel.submitUrl(rawUrl: serverUrl)
    }

    /// Called whenever the app returns to the foreground. iOS has no way to read the Local Network
    /// permission, so the shared ViewModel re-runs the attempt only if the permission is what
    /// blocked it; a still-denied retry fails the same way again.
    func retryAfterLocalNetworkGrant() {
        viewModel.retryAfterLocalNetworkGrant()
    }

    // MARK: - Error → affordance

    /// The action that can fix [error], or nil when there is nothing the user can do from here.
    ///
    /// A Local Network denial maps to Settings: iOS shows its permission prompt once and offers no
    /// API to ask again, and the HIG (Privacy) notes people "view the description — and update their
    /// choice — in Settings". Apple TN3179 describes how the denial is detected.
    static func recovery(for error: any AppError) -> ConnectRecovery? {
        error is ServerConnectErrorLocalNetworkPermissionDenied ? .openSettings : nil
    }

    // MARK: - State mapping

    private func apply(_ state: ServerConnectUiState) {
        switch state.sealedType() {
        case .idle:
            isLoading = false; isVerified = false; error = nil; recovery = nil
        case .verifying:
            isLoading = true; isVerified = false; error = nil; recovery = nil
        case .verified:
            isLoading = false; isVerified = true; error = nil; recovery = nil
        case .error(let errorStateType):
            let errorState = errorStateType.value
            isLoading = false; isVerified = false
            error = errorState.error.message
            recovery = Self.recovery(for: errorState.error)
        }
    }
}
