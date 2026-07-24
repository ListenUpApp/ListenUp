import SwiftUI
@preconcurrency import Shared

/// Login screen with brand styling.
///
/// Navigation is handled by AuthState changes:
/// - On success: AuthState → .authenticated → App shows MainView
/// - Register: Environment action → AuthFlowCoordinator handles it
/// - Change server: Calls disconnectFromServer → AuthState → .needsServerUrl
///
/// No callbacks. State drives everything.
struct LoginView: View {

    // MARK: - Configuration

    let openRegistration: Bool

    // MARK: - Environment

    @Environment(\.navigateToRegister) private var navigateToRegister
    @Environment(\.dependencies) private var dependencies

    // MARK: - State

    @State private var viewModel: LoginViewModelWrapper
    @State private var email = ""
    @State private var password = ""
    @State private var showingClaimInvite = false

    // MARK: - Initialization

    init(openRegistration: Bool = false) {
        self.openRegistration = openRegistration
        // ViewModel initialized immediately, not in onAppear
        // Uses Dependencies.shared since @Environment isn't available in init
        _viewModel = State(initialValue: LoginViewModelWrapper(
            viewModel: Dependencies.shared.makeLoginViewModel()
        ))
    }

    // MARK: - Body

    var body: some View {
        AuthScaffold(
            deep: true,
            nav: AuthNav(label: String(localized: "connect.servers")) {
                Task { try? await dependencies.serverConfig.disconnectFromServer() }
            }
        ) {
            AuthLargeHeader(title: String(localized: "auth.sign_in"))

            // serverSubtitle omitted: ServerConfig has no synchronous currentServerHost
            // accessor (only async getServerUrl/getActiveUrl). Pending a follow-up to
            // wire async host display.

            if let error = viewModel.generalError {
                ErrorBanner(message: error)
            }
            fields
        } footer: {
            signInButton
            footerLinks
        }
    }

    // MARK: - Private views

    private var fields: some View {
        AuthFieldGroup {
            AppTextField(
                placeholder: String(localized: "common.email"),
                text: $email,
                icon: "envelope",
                error: viewModel.emailError,
                isLast: false,
                keyboardType: .emailAddress,
                textContentType: .emailAddress
            )
            AppTextField(
                placeholder: String(localized: "auth.password_label"),
                text: $password,
                kind: .secure,
                error: viewModel.passwordError,
                textContentType: .password
            )
        }
    }

    private var signInButton: some View {
        AuthPrimaryButton(
            title: String(localized: "auth.sign_in"),
            isLoading: viewModel.isLoading
        ) {
            viewModel.login(email: email, password: password)
        }
        .disabled(email.isEmpty || password.isEmpty)
    }

    @ViewBuilder
    private var footerLinks: some View {
        if openRegistration {
            HStack(spacing: 4) {
                Text(String(localized: "auth.dont_have_account"))
                    .foregroundStyle(.secondary)
                Button(String(localized: "auth.sign_up")) { navigateToRegister() }
                    .fontWeight(.semibold)
                    .foregroundStyle(Color.listenUpOrange)
                    .buttonStyle(.plain)
            }
            .font(.subheadline)
        }
        HStack(spacing: 4) {
            Text(String(localized: "invite.have_invite_prompt"))
                .foregroundStyle(.secondary)
            Button(String(localized: "invite.enter_code")) { showingClaimInvite = true }
                .fontWeight(.semibold)
                .foregroundStyle(Color.listenUpOrange)
                .buttonStyle(.plain)
        }
        .font(.subheadline)
        .sheet(isPresented: $showingClaimInvite) {
            ClaimInviteView(onDismiss: { showingClaimInvite = false })
        }
    }
}

// MARK: - Previews

#Preview("Login") {
    LoginView(openRegistration: true)
}

#Preview("Login - No Registration") {
    LoginView(openRegistration: false)
}
