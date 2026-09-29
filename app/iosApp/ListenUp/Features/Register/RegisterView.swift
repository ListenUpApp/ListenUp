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
    @FocusState private var focusedField: RegisterFocusField?

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
        AuthScaffold {
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
            AuthIntro(
                title: String(localized: "auth.create_account"),
                subtitle: String(localized: "auth.admin_account_subtitle")
            ) {
                Label(String(localized: "auth.server_administrator"), systemImage: "checkmark.shield")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(Capsule().fill(Color.luFill))
            }
        } else {
            AuthIntro(title: String(localized: "auth.create_account"))
        }
    }

    private var nameFields: some View {
        AuthFieldGroup {
            AppTextField(placeholder: String(localized: "auth.first_name"),
                         text: $firstName,
                         entry: .givenName, icon: "person", isLast: false,
                         submitLabel: RegisterFocusField.firstName.submitLabel(last: .join),
                         onSubmit: { advance(from: .firstName) })
                .focused($focusedField, equals: .firstName)
            AppTextField(placeholder: String(localized: "auth.last_name"),
                         text: $lastName,
                         entry: .familyName, icon: "person",
                         submitLabel: RegisterFocusField.lastName.submitLabel(last: .join),
                         onSubmit: { advance(from: .lastName) })
                .focused($focusedField, equals: .lastName)
        }
    }

    private var emailField: some View {
        AuthFieldGroup {
            // `.username` (not `.emailAddress`) is the account-identifier content type iOS Password
            // AutoFill pairs with the `.newPassword` fields below, so a tapped credential suggestion
            // actually fills; the `.emailAddress` keyboard still gives the right key layout.
            AppTextField(placeholder: String(localized: "common.email"),
                         text: $email, entry: .accountEmail, icon: "envelope",
                         submitLabel: RegisterFocusField.email.submitLabel(last: .join),
                         onSubmit: { advance(from: .email) })
                .focused($focusedField, equals: .email)
        }
    }

    private var passwordFields: some View {
        AuthFieldGroup {
            AppTextField(placeholder: String(localized: "auth.password_label"),
                         text: $password,
                         entry: .newPassword, kind: .secure, isLast: false,
                         submitLabel: RegisterFocusField.password.submitLabel(last: .join),
                         onSubmit: { advance(from: .password) })
                .focused($focusedField, equals: .password)
            AppTextField(placeholder: String(localized: "auth.confirm_password"),
                         text: $confirmPassword,
                         entry: .newPassword, kind: .secure,
                         error: passwordMismatch ? String(localized: "auth.passwords_dont_match") : nil,
                         submitLabel: RegisterFocusField.confirmPassword.submitLabel(last: .join),
                         onSubmit: { advance(from: .confirmPassword) })
                .focused($focusedField, equals: .confirmPassword)
        }
        .onChange(of: confirmPassword) { _, new in
            passwordMismatch = !new.isEmpty && new != password
        }
        .onChange(of: password) { _, new in
            passwordMismatch = !confirmPassword.isEmpty && confirmPassword != new
        }
    }

    private var registerButton: some View {
        Button {
            register()
        } label: {
            ActionLabel(title: String(localized: "auth.create_account"), isBusy: viewModel.isLoading)
        }
        .prominentAction()
        .disabled(viewModel.isLoading || !isFormValid)
    }

    /// Return walks the fields; Return in Confirm Password creates the account when the form is
    /// complete, exactly as the button would.
    private func advance(from field: RegisterFocusField) {
        FormFocus.advance(from: field, focus: $focusedField) {
            if isFormValid, !viewModel.isLoading { register() }
        }
    }

    private func register() {
        if validateForm() {
            viewModel.register(email: email, password: password,
                               firstName: firstName, lastName: lastName)
        }
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
