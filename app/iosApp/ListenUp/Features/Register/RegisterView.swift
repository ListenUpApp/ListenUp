import SwiftUI
import Shared

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
            viewModel: Dependencies.shared.makeRegisterViewModel()
        ))
    }

    // MARK: - Admin badge helper (pure, unit-tested)

    /// Whether to surface the "Server administrator" badge + admin copy. Pure so it can
    /// be unit-tested; defaults to the generic path until a first-run signal exists.
    static func showsAdminBadge(isFirstRun: Bool) -> Bool { isFirstRun }

    private var showsAdminBadge: Bool { Self.showsAdminBadge(isFirstRun: false) }

    // MARK: - Body

    var body: some View {
        AuthScaffold(nav: AuthNav(label: String(localized: "common.back")) { navigateBack() }) {
            header
            if let error = viewModel.error {
                ErrorBanner(message: error)
            }
            nameFields
            emailField
            passwordFields
        } footer: {
            registerButton
            loginLink
        }
    }

    // MARK: - Private views

    @ViewBuilder
    private var header: some View {
        if showsAdminBadge {
            AuthLargeHeader(
                title: String(localized: "auth.create_account"),
                subtitle: String(localized: "auth.admin_account_subtitle")
            ) {
                Label(String(localized: "auth.server_administrator"), systemImage: "checkmark.shield")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.listenUpOrange)
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(Capsule().fill(Color.listenUpOrange.opacity(0.15)))
            }
        } else {
            AuthLargeHeader(title: String(localized: "auth.create_account"))
        }
    }

    private var nameFields: some View {
        AuthFieldGroup {
            AppTextField(placeholder: String(localized: "auth.first_name"),
                         text: $firstName, icon: "person", isLast: false,
                         textContentType: .givenName, autocapitalization: .words)
            AppTextField(placeholder: String(localized: "auth.last_name"),
                         text: $lastName, icon: "person", textContentType: .familyName,
                         autocapitalization: .words)
        }
    }

    private var emailField: some View {
        AuthFieldGroup {
            // `.username` (not `.emailAddress`) is the account-identifier content type iOS Password
            // AutoFill pairs with the `.newPassword` fields below, so a tapped credential suggestion
            // actually fills; the `.emailAddress` keyboard still gives the right key layout.
            AppTextField(placeholder: String(localized: "common.email"),
                         text: $email, icon: "envelope", keyboardType: .emailAddress,
                         textContentType: .username)
        }
    }

    private var passwordFields: some View {
        AuthFieldGroup {
            AppTextField(placeholder: String(localized: "auth.password_label"),
                         text: $password, kind: .secure, isLast: false,
                         textContentType: .newPassword)
            AppTextField(placeholder: String(localized: "auth.confirm_password"),
                         text: $confirmPassword, kind: .secure,
                         error: passwordMismatch ? String(localized: "auth.passwords_dont_match") : nil,
                         textContentType: .newPassword)
        }
        .onChange(of: confirmPassword) { _, new in
            passwordMismatch = !new.isEmpty && new != password
        }
        .onChange(of: password) { _, new in
            passwordMismatch = !confirmPassword.isEmpty && confirmPassword != new
        }
    }

    private var registerButton: some View {
        AuthPrimaryButton(
            title: String(localized: "auth.create_account"),
            isLoading: viewModel.isLoading
        ) {
            if validateForm() {
                viewModel.register(email: email, password: password,
                                   firstName: firstName, lastName: lastName)
            }
        }
        .disabled(!isFormValid)
    }

    private var loginLink: some View {
        HStack(spacing: 4) {
            Text(String(localized: "auth.already_have_account")).foregroundStyle(.secondary)
            Button(String(localized: "auth.sign_in")) { navigateBack() }
                .fontWeight(.semibold).foregroundStyle(Color.listenUpOrange).buttonStyle(.plain)
        }
        .font(.subheadline)
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

// MARK: - Previews

#Preview("Register") {
    RegisterView()
}
