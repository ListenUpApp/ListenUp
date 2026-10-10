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
        bridge.own(viewModel)
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
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
        switch state.sealedType() {
        case .idle:
            isLoading = false; isSuccess = false; clearErrors()
        case .loading:
            isLoading = true; isSuccess = false; clearErrors()
        case .success:
            isLoading = false; isSuccess = true; clearErrors()
        case .error(let errorType):
            let error = errorType.value
            isLoading = false; isSuccess = false; mapError(error.type)
        }
    }

    private func clearErrors() {
        validationField = nil
        generalError = nil
    }

    private func mapError(_ errorType: SetupErrorType) {
        clearErrors()
        switch errorType.sealedType() {
        case .validationError(let validationType):
            let validation = validationType.value
            validationField = SetupValidation.errorField(for: validation.field)
        case .alreadyConfigured:
            generalError = String(localized: "setup.error_already_configured")
        case .networkError:
            generalError = String(localized: "auth.unable_to_connect")
        case .serverError:
            generalError = String(localized: "auth.server_error")
        }
    }
}
