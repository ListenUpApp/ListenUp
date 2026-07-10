import AppIntents
import ListenUpActivityKit
import SwiftUI
import UIKit
@preconcurrency import Shared

@main
struct ListenUpApp: App {
    init() {
        // Koin must be initialised before any UI (or observer) accesses it.
        ExportedKotlinPackages.com.calypsan.listenup.client.di.startDependencyInjection()
        Log.info("ListenUp iOS app initialized")
        // Make the app's player available to the Live Activity intents.
        AppDependencyManager.shared.add(dependency: PlaybackController() as any PlaybackControlling)
        // Make the "resume my book" read available to ResumePlaybackIntent (Siri / Control Center).
        AppDependencyManager.shared.add(dependency: LastPlayedBookProvider() as any LastPlayedBookProviding)
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
    @State private var hapticsSettings = HapticsSettings()
    @State private var deepLinkRouter = DeepLinkRouter()
    @State private var syncSession: SyncSessionController?
    @State private var showReauthSheet = false
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dependencies) private var dependencies

    var body: some View {
        content
            .environment(currentUser)
            .environment(hapticsSettings)
            .environment(deepLinkRouter)
            // App-wide server-reachability signal (offline indicators + Retry across screens).
            .environment(dependencies.serverReachabilityObserver)
            // Universal links: `.onOpenURL` is the reliable SwiftUI App-lifecycle delivery path
            // (cold launch *and* while running). `.onContinueUserActivity(NSUserActivityTypeBrowsingWeb)`
            // does not fire for universal links under the SwiftUI lifecycle — kept only as a
            // belt-and-suspenders and to confirm delivery in logs.
            .onOpenURL { url in
                Log.info("onOpenURL fired: \(url.host ?? "?")\(url.path)")
                deepLinkRouter.receive(url: url)
            }
            .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                Log.info("onContinueUserActivity fired: \(activity.webpageURL?.host ?? "nil")")
                if let url = activity.webpageURL { deepLinkRouter.receive(url: url) }
            }
            .sheet(isPresented: invitePresented) {
                if case .claimInvite(let serverURL, let code, let remoteURL) = deepLinkRouter.outcome {
                    ClaimInviteView(deepLinkServerURL: serverURL, deepLinkCode: code, deepLinkRemoteURL: remoteURL) {
                        deepLinkRouter.consume()
                    }
                }
            }
            .animation(.smooth(duration: 0.3), value: auth.state)
            // Start realtime sync once authenticated (initial pull + SSE firehose), mirroring the
            // Compose `MainActivity`/`AppShell`. Without this the library never populates on iOS.
            .onChange(of: auth.state, initial: true) { _, newState in
                // Dismiss the re-auth sheet the moment the user is authenticated again; the engine
                // auth gate (shared) owns resuming the firehose + forced reconcile.
                if newState == .authenticated { showReauthSheet = false }
                activateSyncIfAuthenticated()
            }
            .onChange(of: scenePhase) { _, newPhase in
                if newPhase == .active {
                    // Reconnect realtime sync on every foreground (single-flight, so safe).
                    activateSyncIfAuthenticated()
                    return
                }
                guard ScenePhasePolicy.shouldSavePosition(on: newPhase) else { return }

                let coordinator = dependencies.playerCoordinator
                var taskId: UIBackgroundTaskIdentifier = .invalid
                taskId = UIApplication.shared.beginBackgroundTask(withName: "save-position") {
                    // Expiration handler: the system reclaimed our time — end the assertion to
                    // avoid an unbalanced-background-task termination.
                    UIApplication.shared.endBackgroundTask(taskId)
                    taskId = .invalid
                }
                guard taskId != .invalid else { return }   // background time denied; nothing to do

                Task { @MainActor in
                    defer {
                        if taskId != .invalid {
                            UIApplication.shared.endBackgroundTask(taskId)
                            taskId = .invalid
                        }
                    }
                    await coordinator.saveCurrentPosition()
                }
            }
    }

    private var invitePresented: Binding<Bool> {
        Binding(
            get: { if case .claimInvite = deepLinkRouter.outcome { true } else { false } },
            set: { presented in if !presented { deepLinkRouter.consume() } }
        )
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
        case .sessionLapsed:
            // Shell stays mounted (M2/M3): library, downloads, playback all work. The banner’s
            // Sign-in presents the login flow as a dismissable sheet — never a forced wall.
            authenticatedContent
                .safeAreaInset(edge: .top) {
                    SessionLapsedBanner(onSignIn: { showReauthSheet = true })
                }
                .sheet(isPresented: $showReauthSheet) {
                    AuthFlowCoordinator(openRegistration: false)
                }
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
