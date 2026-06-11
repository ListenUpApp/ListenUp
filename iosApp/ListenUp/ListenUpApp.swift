import AppIntents
import ListenUpActivityKit
import SwiftUI
import UIKit
@preconcurrency import Shared

@main
struct ListenUpApp: App {
    init() {
        // Koin must be initialised before any UI (or observer) accesses it.
        Koin_iosKt.initializeKoin(additionalModules: [])
        Log.info("ListenUp iOS app initialized")
        // Make the app's player available to the Live Activity intents.
        AppDependencyManager.shared.add(dependency: PlaybackController() as any PlaybackControlling)
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
    @State private var readiness = LibraryReadinessObserver()
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dependencies) private var dependencies

    var body: some View {
        content
            .environment(currentUser)
            .animation(.smooth(duration: 0.3), value: auth.state)
            .onChange(of: scenePhase) { _, newPhase in
                guard newPhase == .background || newPhase == .inactive else { return }
                let coordinator = dependencies.playerCoordinator
                let taskId = UIApplication.shared.beginBackgroundTask(withName: "save-position")
                Task { @MainActor in
                    await coordinator.saveCurrentPosition()
                    UIApplication.shared.endBackgroundTask(taskId)
                }
            }
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
            authenticatedContent
        }
    }

    /// Gate the authenticated window on library readiness. A first-run admin with no library
    /// (`needsSetup`) is routed into the setup wizard; on completion the readiness latch flips
    /// to `ready` and the main app mounts. A returning user with a library is `ready` (or
    /// `populating`/`checkFailed`) and goes straight to the app — setup is skipped.
    @ViewBuilder
    private var authenticatedContent: some View {
        switch readiness.phase {
        case .checking:
            LaunchScreen()
        case .needsSetup:
            LibrarySetupFlowCoordinator(onComplete: { readiness.onLibrarySetupComplete() })
        case .populating, .ready, .checkFailed:
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
