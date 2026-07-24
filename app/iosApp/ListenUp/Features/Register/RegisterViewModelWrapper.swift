import Foundation
import Shared

/// Observes `RegisterViewModel`'s `state` flow, flattening `RegisterUiState`
/// into SwiftUI-native properties. Thin over `FlowBridge`.
@Observable
@MainActor
final class RegisterViewModelWrapper {
    private(set) var isLoading: Bool = false
    private(set) var isSuccess: Bool = false
    private(set) var error: String?

    private let viewModel: RegisterViewModel
    private let bridge = FlowBridge()

    init(viewModel: RegisterViewModel) {
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

    func register(email: String, password: String, firstName: String, lastName: String) {
        viewModel.onRegisterSubmit(
            email: email, password: password, firstName: firstName, lastName: lastName
        )
    }

    func clearError() {
        viewModel.clearError()
    }

    // MARK: - State mapping

    private func apply(_ state: RegisterUiState) {
        switch onEnum(of: state) {
        case .idle:
            isLoading = false; isSuccess = false; error = nil
        case .loading:
            isLoading = true; isSuccess = false; error = nil
        case .success:
            isLoading = false; isSuccess = true; error = nil
        case .error(let errorState):
            isLoading = false; isSuccess = false; error = errorState.message
        case .unknown:
            Log.error("Unexpected RegisterUiState case")
            isLoading = false; isSuccess = false; error = String(localized: "common.error")
        }
    }
}
