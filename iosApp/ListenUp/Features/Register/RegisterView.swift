import SwiftUI
@preconcurrency import Shared

/// Registration screen with brand styling.
///
/// On success, AuthState transitions automatically (either to .authenticated
/// or .pendingApproval depending on server config). No callback needed.
struct RegisterView: View {

    // MARK: - Environment

    @Environment(\.navigateBack) private var navigateBack

    // MARK: - State

    @State private var viewModel: RegisterViewModelWrapper
    @State private var firstName = ""
    @State private var lastName = ""
    @State private var email = ""
    @State private var password = ""
    @State private var confirmPassword = ""
    @State private var passwordMismatch = false

    // MARK: - Initialization

    init() {
        _viewModel = State(initialValue: RegisterViewModelWrapper(
            viewModel: Dependencies.shared.registerViewModel
        ))
    }

    // MARK: - Body

    var body: some View {
        AuthScreenLayout {
            formContent
        }
    }

    // MARK: - Form Content

    @ViewBuilder
    private var formContent: some View {
        VStack(alignment: .leading, spacing: 20) {
            header
            fields
            errorMessage
            registerButton
            loginLink
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "auth.create_account"))
                .font(.largeTitle.bold())

            Text(String(localized: "auth.join_listenup"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }

    private var fields: some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                GlassTextField(
                    label: String(localized: "auth.first_name"),
                    placeholder: String(localized: "auth.first_name_placeholder"),
                    text: $firstName,
                    textContentType: .givenName,
                    autocapitalization: .words
                )

                GlassTextField(
                    label: String(localized: "auth.last_name"),
                    placeholder: String(localized: "auth.last_name_placeholder"),
                    text: $lastName,
                    textContentType: .familyName,
                    autocapitalization: .words
                )
            }

            GlassTextField(
                label: String(localized: "common.email"),
                placeholder: String(localized: "auth.email_placeholder"),
                text: $email,
                keyboardType: .emailAddress,
                textContentType: .emailAddress
            )

            GlassSecureField(
                label: String(localized: "auth.password_label"),
                placeholder: String(localized: "auth.create_password_placeholder"),
                text: $password
            )

            GlassSecureField(
                label: String(localized: "auth.confirm_password"),
                placeholder: String(localized: "auth.confirm_password_placeholder"),
                text: $confirmPassword,
                error: passwordMismatch ? String(localized: "auth.passwords_dont_match") : nil
            )
            .onChange(of: confirmPassword) { _, newValue in
                passwordMismatch = !newValue.isEmpty && newValue != password
            }
            .onChange(of: password) { _, newValue in
                passwordMismatch = !confirmPassword.isEmpty && confirmPassword != newValue
            }
        }
    }

    @ViewBuilder
    private var errorMessage: some View {
        if let error = viewModel.error {
            ErrorBanner(message: error)
        }
    }

    private var registerButton: some View {
        ListenUpButton(
            title: String(localized: "auth.create_account"),
            isLoading: viewModel.isLoading
        ) {
            if validateForm() {
                viewModel.register(
                    email: email,
                    password: password,
                    firstName: firstName,
                    lastName: lastName
                )
            }
        }
        .disabled(!isFormValid)
    }

    private var loginLink: some View {
        HStack(spacing: 4) {
            Text(String(localized: "auth.already_have_account"))
                .foregroundStyle(.secondary)

            Button(String(localized: "auth.sign_in")) {
                navigateBack()
            }
            .fontWeight(.semibold)
            .foregroundStyle(Color.listenUpOrange)
            .buttonStyle(.plain)
        }
        .font(.subheadline)
        .frame(maxWidth: .infinity)
    }

    // MARK: - Validation

    private var isFormValid: Bool {
        !firstName.isEmpty &&
        !lastName.isEmpty &&
        !email.isEmpty &&
        !password.isEmpty &&
        !confirmPassword.isEmpty &&
        password == confirmPassword
    }

    private func validateForm() -> Bool {
        if password != confirmPassword {
            passwordMismatch = true
            return false
        }
        return isFormValid
    }
}

// MARK: - Error Banner (shared component, could be extracted)

// MARK: - Previews

#Preview("Register") {
    RegisterView()
}
