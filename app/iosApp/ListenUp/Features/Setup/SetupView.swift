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
    @FocusState private var focusedField: SetupFocusField?

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
        AuthIntro(
            title: String(localized: "auth.create_admin_account"),
            subtitle: String(localized: "auth.admin_account_subtitle")
        ) {
            Label(String(localized: "auth.server_administrator"), systemImage: "checkmark.shield")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)                .padding(.horizontal, Spacing.s).padding(.vertical, Spacing.xs)
                .background(Capsule().fill(Color.luFill))
        }
    }

    private var nameFields: some View {
        AuthFieldGroup {
            AppTextField(
                placeholder: String(localized: "auth.first_name"),
                text: $firstName,
                entry: .givenName,
                icon: "person",
                error: viewModel.validationField == .firstName
                    ? String(localized: "setup.error_first_name_required") : nil,
                isLast: false,
                submitLabel: SetupFocusField.firstName.submitLabel(last: .done),
                onSubmit: { advance(from: .firstName) }
            )
            .focused($focusedField, equals: .firstName)
            AppTextField(
                placeholder: String(localized: "auth.last_name"),
                text: $lastName,
                entry: .familyName,
                icon: "person",
                error: viewModel.validationField == .lastName
                    ? String(localized: "setup.error_last_name_required") : nil,
                submitLabel: SetupFocusField.lastName.submitLabel(last: .done),
                onSubmit: { advance(from: .lastName) }
            )
            .focused($focusedField, equals: .lastName)
        }
    }

    private var emailField: some View {
        AuthFieldGroup {
            AppTextField(
                placeholder: String(localized: "common.email"),
                text: $email,
                entry: .email,
                icon: "envelope",
                error: viewModel.validationField == .email
                    ? String(localized: "auth.invalid_email") : nil,
                submitLabel: SetupFocusField.email.submitLabel(last: .done),
                onSubmit: { advance(from: .email) }
            )
            .focused($focusedField, equals: .email)
        }
    }

    private var passwordFields: some View {
        AuthFieldGroup {
            AppTextField(
                placeholder: String(localized: "auth.password_label"),
                text: $password,
                entry: .newPassword,
                kind: .secure,
                error: viewModel.validationField == .password
                    ? String(localized: "setup.error_weak_password") : nil,
                isLast: false,
                submitLabel: SetupFocusField.password.submitLabel(last: .done),
                onSubmit: { advance(from: .password) }
            )
            .focused($focusedField, equals: .password)
            AppTextField(
                placeholder: String(localized: "auth.confirm_password"),
                text: $confirm,
                entry: .newPassword,
                kind: .secure,
                error: confirmError,
                submitLabel: SetupFocusField.confirmPassword.submitLabel(last: .done),
                onSubmit: { advance(from: .confirmPassword) }
            )
            .focused($focusedField, equals: .confirmPassword)
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
        Button {
            submit()
        } label: {
            ActionLabel(title: String(localized: "auth.create_account"), isBusy: viewModel.isLoading)
        }
        .prominentAction()
        .disabled(viewModel.isLoading || !isFormReady)
    }

    /// Return walks the fields; Return in Confirm Password creates the account once the form is
    /// ready, exactly as the button would.
    private func advance(from field: SetupFocusField) {
        FormFocus.advance(from: field, focus: $focusedField) {
            if isFormReady, !viewModel.isLoading { submit() }
        }
    }

    private func submit() {
        viewModel.submit(
            firstName: firstName,
            lastName: lastName,
            email: email,
            password: password,
            confirm: confirm
        )
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
