import SwiftUI
@preconcurrency import Shared

@main
struct ListenUpApp: App {
    init() {
        // Koin must be initialised before any UI (or observer) accesses it.
        Koin_iosKt.initializeKoin(additionalModules: [])
        Log.info("ListenUp iOS app initialized")
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .tint(Color.listenUpOrange)
        }
    }
}

/// Root view — created after `App.init()` so observers resolve Koin safely.
private struct RootView: View {
    @State private var auth = AuthStateObserver()
    @State private var currentUser = CurrentUserObserver()

    var body: some View {
        content
            .environment(currentUser)
            .animation(.smooth(duration: 0.3), value: auth.state)
    }

    @ViewBuilder
    private var content: some View {
        switch auth.state {
        case .initializing, .checkingServer:
            LaunchScreen()
        case .needsServerUrl, .needsSetup:
            ServerFlowCoordinator()
        case .needsLogin:
            AuthFlowCoordinator(openRegistration: auth.openRegistration)
        case .pendingApproval:
            PendingApprovalView()
        case .authenticated:
            MainTabView()
        }
    }
}

/// Shown during app initialisation.
private struct LaunchScreen: View {
    var body: some View {
        ZStack {
            Color.brandGradient.ignoresSafeArea()
            Image("listenup_logo_white")
                .resizable()
                .scaledToFit()
                .frame(width: 120, height: 120)
        }
    }
}

private struct PendingApprovalView: View {
    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "clock.badge.checkmark")
                .font(.system(size: 64))
                .foregroundStyle(Color.listenUpOrange)
            Text(String(localized: "auth.waiting_for_approval"))
                .font(.title.bold())
            Text(String(localized: "auth.pending_approval_message"))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
    }
}
