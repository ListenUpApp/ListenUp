import Foundation
import Shared

/// Observes `SetupViewModel`'s `state` flow, flattening `SetupUiState` into
/// SwiftUI-native properties. Thin over `FlowBridge`.
@Observable
@MainActor
final class SetupViewModelWrapper {
    private(set) var isLoading: Bool = false
    private(set) var isSuccess: Bool = false
    /// Set when a specific form field failed validation — used to highlight the right input.
    private(set) var validationField: SetupFieldKey?
    /// Set for non-field errors (network, server, already-configured).
    private(set) var generalError: String?

    private let viewModel: SetupViewModel
    private let bridge = FlowBridge()

    init(viewModel: SetupViewModel) {
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

    func submit(firstName: String, lastName: String, email: String, password: String, confirm: String) {
        viewModel.onSetupSubmit(
            firstName: firstName,
            lastName: lastName,
            email: email,
            password: password,
            passwordConfirm: confirm
        )
    }

    func clearError() {
        viewModel.clearError()
    }

    // MARK: - State mapping

    private func apply(_ state: SetupUiState) {
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
            Log.error("Unexpected SetupUiState case")
            isLoading = false; isSuccess = false
            clearErrors(); generalError = String(localized: "common.something_went_wrong")
        }
    }

    private func clearErrors() {
        validationField = nil
        generalError = nil
    }

    private func mapError(_ errorType: SetupErrorType) {
        clearErrors()
        switch onEnum(of: errorType) {
        case .validationError(let validation):
            validationField = SetupValidation.errorField(for: validation.field)
        case .alreadyConfigured:
            generalError = String(localized: "setup.error_already_configured")
        case .networkError:
            generalError = String(localized: "auth.unable_to_connect")
        case .serverError:
            generalError = String(localized: "auth.server_error")
        case .unknown:
            Log.error("Unexpected SetupErrorType case")
            generalError = String(localized: "common.something_went_wrong")
        }
    }
}
