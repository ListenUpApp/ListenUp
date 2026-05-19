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

private struct NavigateToRegisterKey: EnvironmentKey {
    nonisolated(unsafe) static let defaultValue: () -> Void = {}
}

private struct NavigateBackKey: EnvironmentKey {
    nonisolated(unsafe) static let defaultValue: () -> Void = {}
}

extension EnvironmentValues {
    var navigateToRegister: () -> Void {
        get { self[NavigateToRegisterKey.self] }
        set { self[NavigateToRegisterKey.self] = newValue }
    }
    var navigateBack: () -> Void {
        get { self[NavigateBackKey.self] }
        set { self[NavigateBackKey.self] = newValue }
    }
}
