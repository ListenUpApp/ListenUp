import SwiftUI

/// Coordinates the auth flow: Login ↔ Register. On success the KMP layer moves
/// `AuthState` to `.authenticated`, which transitions the app — no success callback.
struct AuthFlowCoordinator: View {
    let openRegistration: Bool

    @State private var showingRegister = false

    var body: some View {
        NavigationStack {
            LoginView(openRegistration: openRegistration)
                .navigationDestination(isPresented: $showingRegister) {
                    RegisterView()
                }
                .environment(\.navigateToRegister) { showingRegister = true }
                .environment(\.navigateBack) { showingRegister = false }
        }
    }
}

// MARK: - Navigation environment keys

extension EnvironmentValues {
    @Entry var navigateToRegister: () -> Void = {}
    @Entry var navigateBack: () -> Void = {}
}
