import SwiftUI

/// Coordinates the auth flow: Login ↔ Register ↔ Forgot password. On success the KMP layer
/// moves `AuthState` to `.authenticated`, which transitions the app — no success callback.
struct AuthFlowCoordinator: View {
    let openRegistration: Bool

    @State private var showingRegister = false
    @State private var showingForgotPassword = false

    var body: some View {
        NavigationStack {
            LoginView(openRegistration: openRegistration)
                .navigationDestination(isPresented: $showingRegister) {
                    RegisterView()
                }
                .navigationDestination(isPresented: $showingForgotPassword) {
                    ForgotPasswordView()
                }
                .environment(\.navigateToRegister) { showingRegister = true }
                .environment(\.navigateToForgotPassword) { showingForgotPassword = true }
                .environment(\.navigateBack) {
                    showingRegister = false
                    showingForgotPassword = false
                }
        }
    }
}

// MARK: - Navigation environment keys

extension EnvironmentValues {
    @Entry var navigateToRegister: () -> Void = {}
    @Entry var navigateToForgotPassword: () -> Void = {}
    @Entry var navigateBack: () -> Void = {}
}
