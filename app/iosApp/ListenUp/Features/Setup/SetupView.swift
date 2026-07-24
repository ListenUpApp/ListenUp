import SwiftUI
import Shared

/// Create-admin-account screen shown once when the server has no users yet.
///
/// Navigation is fully automatic — on success `SetupViewModel` persists tokens,
/// flipping `AuthState` to `.authenticated`, and `RootView` transitions to
/// `MainTabView`. No back button; setup is a one-way first-run gate.
struct SetupView: View {

    // MARK: - State

    @State private var viewModel: SetupViewModelWrapper
    @State private var firstName = ""
    @State private var lastName = ""
    @State private var email = ""
    @State private var password = ""
    @State private var confirm = ""

    // MARK: - Initialization

    init() {
        _viewModel = State(initialValue: SetupViewModelWrapper(
            viewModel: Dependencies.shared.makeSetupViewModel()
        ))
    }

    // MARK: - Body

    var body: some View {
        AuthScaffold {
            header
            if let error = viewModel.generalError {
                ErrorBanner(message: error)
            }
            nameFields
            emailField
            passwordFields
        } footer: {
            createButton
        }
    }

    // MARK: - Private views

    private var header: some View {
        AuthLargeHeader(
            title: String(localized: "auth.create_admin_account"),
            subtitle: String(localized: "auth.admin_account_subtitle")
        ) {
            Label(String(localized: "auth.server_administrator"), systemImage: "checkmark.shield")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.listenUpOrange)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(Capsule().fill(Color.listenUpOrange.opacity(0.15)))
        }
    }

    private var nameFields: some View {
        AuthFieldGroup {
            AppTextField(
                placeholder: String(localized: "auth.first_name"),
                text: $firstName,
                icon: "person",
                error: viewModel.validationField == .firstName
                    ? String(localized: "setup.error_first_name_required") : nil,
                isLast: false,
                textContentType: .givenName,
                autocapitalization: .words
            )
            AppTextField(
                placeholder: String(localized: "auth.last_name"),
                text: $lastName,
                icon: "person",
                error: viewModel.validationField == .lastName
                    ? String(localized: "setup.error_last_name_required") : nil,
                textContentType: .familyName,
                autocapitalization: .words
            )
        }
    }

    private var emailField: some View {
        AuthFieldGroup {
            AppTextField(
                placeholder: String(localized: "common.email"),
                text: $email,
                icon: "envelope",
                error: viewModel.validationField == .email
                    ? String(localized: "auth.invalid_email") : nil,
                keyboardType: .emailAddress,
                textContentType: .emailAddress
            )
        }
    }

    private var passwordFields: some View {
        AuthFieldGroup {
            AppTextField(
                placeholder: String(localized: "auth.password_label"),
                text: $password,
                kind: .secure,
                error: viewModel.validationField == .password
                    ? String(localized: "setup.error_weak_password") : nil,
                isLast: false,
                textContentType: .newPassword
            )
            AppTextField(
                placeholder: String(localized: "auth.confirm_password"),
                text: $confirm,
                kind: .secure,
                error: confirmError,
                textContentType: .newPassword
            )
        }
    }

    /// Inline error for the confirm field: server-flagged mismatch takes precedence over
    /// the client-side check so we don't double-show a message.
    private var confirmError: String? {
        if viewModel.validationField == .passwordConfirm {
            return String(localized: "auth.passwords_dont_match")
        }
        if SetupValidation.passwordMismatch(password: password, confirm: confirm) {
            return String(localized: "auth.passwords_dont_match")
        }
        return nil
    }

    private var createButton: some View {
        AuthPrimaryButton(
            title: String(localized: "auth.create_account"),
            isLoading: viewModel.isLoading
        ) {
            viewModel.submit(
                firstName: firstName,
                lastName: lastName,
                email: email,
                password: password,
                confirm: confirm
            )
        }
        .disabled(!isFormReady)
    }

    /// All fields filled and passwords match client-side before we even hit the network.
    private var isFormReady: Bool {
        !firstName.isEmpty &&
        !lastName.isEmpty &&
        !email.isEmpty &&
        !password.isEmpty &&
        !confirm.isEmpty &&
        !SetupValidation.passwordMismatch(password: password, confirm: confirm)
    }
}

// MARK: - Previews

#Preview("Setup") {
    SetupView()
}
