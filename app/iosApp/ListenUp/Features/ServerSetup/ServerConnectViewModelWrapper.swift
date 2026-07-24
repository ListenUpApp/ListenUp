import Foundation
import Shared

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

    // MARK: - State mapping

    private func apply(_ state: ServerConnectUiState) {
        switch onEnum(of: state) {
        case .idle:
            isLoading = false; isVerified = false; error = nil
        case .verifying:
            isLoading = true; isVerified = false; error = nil
        case .verified:
            isLoading = false; isVerified = true; error = nil
        case .error(let errorState):
            isLoading = false; isVerified = false; error = errorState.error.message
        case .unknown:
            Log.error("Unexpected ServerConnectUiState case")
            isLoading = false; isVerified = false; error = String(localized: "common.something_went_wrong")
        }
    }
}
