import Foundation
import Shared

/// Observes `LoginViewModel`'s `state` flow, flattening `LoginUiState` into
/// SwiftUI-native properties. Thin over `FlowBridge`.
@Observable
@MainActor
final class LoginViewModelWrapper {
    private(set) var isLoading: Bool = false
    private(set) var isSuccess: Bool = false
    private(set) var emailError: String?
    private(set) var passwordError: String?
    private(set) var generalError: String?

    private let viewModel: LoginViewModel
    private let bridge = FlowBridge()

    init(viewModel: LoginViewModel) {
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

    func login(email: String, password: String) {
        viewModel.onLoginSubmit(email: email, password: password)
    }

    func clearError() {
        viewModel.clearError()
    }

    // MARK: - State mapping

    private func apply(_ state: LoginUiState) {
        switch onEnum(of: state) {
        case .idle:
            isLoading = false; isSuccess = false; clearErrors()
        case .loading:
            isLoading = true; isSuccess = false; clearErrors()
        case .success:
            isLoading = false; isSuccess = true; clearErrors()
        case .error(let error):
            isLoading = false; isSuccess = false; mapError(error.type)
        case .unknown:
            Log.error("Unexpected LoginUiState case")
            isLoading = false; isSuccess = false
            clearErrors()
            generalError = String(localized: "auth.server_error")
        }
    }

    private func clearErrors() {
        emailError = nil
        passwordError = nil
        generalError = nil
    }

    private func mapError(_ errorType: LoginErrorType) {
        clearErrors()
        switch onEnum(of: errorType) {
        case .invalidCredentials:
            generalError = String(localized: "auth.invalid_credentials")
        case .networkError(let error):
            generalError = error.detail ?? String(localized: "auth.unable_to_connect")
        case .serverError(let error):
            generalError = error.detail ?? String(localized: "auth.server_error")
        case .validationError(let error):
            switch error.field {
            case .email: emailError = String(localized: "auth.invalid_email")
            case .password: passwordError = String(localized: "auth.enter_password")
            }
        case .unknown:
            Log.error("Unexpected LoginErrorType case")
            generalError = String(localized: "auth.server_error")
        }
    }
}
