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
    @State private var syncSession: SyncSessionController?
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dependencies) private var dependencies

    var body: some View {
        content
            .environment(currentUser)
            .animation(.smooth(duration: 0.3), value: auth.state)
            // Start realtime sync once authenticated (initial pull + SSE firehose), mirroring the
            // Compose `MainActivity`/`AppShell`. Without this the library never populates on iOS.
            .onChange(of: auth.state, initial: true) { _, _ in
                activateSyncIfAuthenticated()
            }
            .onChange(of: scenePhase) { _, newPhase in
                if newPhase == .active {
                    // Reconnect realtime sync on every foreground (single-flight, so safe).
                    activateSyncIfAuthenticated()
                    return
                }
                guard newPhase == .background || newPhase == .inactive else { return }
                let coordinator = dependencies.playerCoordinator
                let taskId = UIApplication.shared.beginBackgroundTask(withName: "save-position")
                Task { @MainActor in
                    await coordinator.saveCurrentPosition()
                    UIApplication.shared.endBackgroundTask(taskId)
                }
            }
    }

    /// Connect realtime sync + resume downloads when authenticated. Lazily builds the controller
    /// from the shared `SyncRepository`/`DownloadService` on first use.
    private func activateSyncIfAuthenticated() {
        guard auth.state == .authenticated else { return }
        let controller = syncSession ?? SyncSessionController(
            connectRealtime: {
                do {
                    try await dependencies.syncRepository.connectRealtime()
                } catch is CancellationError {
                } catch {
                    // Realtime sync is best-effort: pull-to-refresh is the manual fallback
                    // (Never Stranded), but the failure must not vanish — log it.
                    Log.error("Realtime sync connect failed", error: error)
                }
            },
            resumeDownloads: {
                do {
                    try await dependencies.downloadService.resumeIncompleteDownloads()
                } catch is CancellationError {
                } catch {
                    Log.error("Resume incomplete downloads failed", error: error)
                }
            }
        )
        syncSession = controller
        controller.activate()
    }

    @ViewBuilder
    private var content: some View {
        switch auth.state {
        case .initializing, .checkingServer:
            LaunchScreen()
        case .needsServerUrl:
            ServerFlowCoordinator()
        case .needsSetup:
            SetupView()
        case .needsLogin:
            AuthFlowCoordinator(openRegistration: auth.openRegistration)
        case .pendingApproval:
            PendingApprovalView(userId: auth.pendingApprovalUserId, email: auth.pendingApprovalEmail)
        case .authenticated:
            authenticatedContent
        }
    }

    /// Gate the authenticated window on library readiness. A first-run admin with no library
    /// (`needsSetup`) is routed into the setup wizard; on completion the readiness latch flips
    /// to `populating` while the initial scan runs, then `ready` and the main app mounts. The
    /// `populating` gate shows the "Building your library" screen instead of an empty shell —
    /// it fires only for the **initial** population (a returning user with books in Room is
    /// `ready` immediately; later background scans never re-arm it). A returning user goes
    /// straight to the app — setup is skipped.
    @ViewBuilder
    private var authenticatedContent: some View {
        switch readiness.phase {
        case .checking:
            LaunchScreen()
        case .needsSetup:
            LibrarySetupFlowCoordinator(onComplete: { readiness.onLibrarySetupComplete() })
        case .populating:
            LibraryScanView(progress: readiness.scanProgress)
        case .ready, .checkFailed:
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
