import AppIntents
import ListenUpActivityKit
import SwiftUI
import UIKit
@preconcurrency import Shared

@main
struct ListenUpApp: App {
    /// Present solely to answer `configurationForConnecting` for the CarPlay scene role;
    /// see `AppDelegate`. SwiftUI still owns the window scene.
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        // Koin must be initialised before any UI (or observer) accesses it.
        ExportedKotlinPackages.com.calypsan.listenup.client.di.startDependencyInjection()
        // Device-local preferences — haptics, auto-rewind, Wi-Fi-only downloads — sit behind a
        // suspend read, so they arrive as a boot step or not at all: the shared StateFlows start on
        // hard-coded defaults and nothing else ever replaces them. Started here rather than awaited
        // in a view because every consumer observes those flows and re-reads on emission, so the
        // only thing that matters is that it starts as soon as Koin exists. A failure leaves the
        // defaults in place — exactly the behaviour before this call — and must never block launch.
        Task { try? await KoinHelper.shared.initializeLocalPreferences() }
        // Before any image view: covers build their requests synchronously from the mirrored server
        // URL, and the pipeline's loader authenticates them (see `ListenUpImagePipeline`).
        ListenUpImagePipeline.install()
        ImageServerBase.shared.startObserving(KoinHelper.shared.getServerConfig())
        Log.info("ListenUp iOS app initialized")
        // Make the app's player available to the playback App Intents (Siri, Shortcuts, Control Center).
        AppDependencyManager.shared.add(dependency: PlaybackController() as any PlaybackControlling)
        // Make the "resume my book" read available to ResumePlaybackIntent (Siri / Control Center).
        AppDependencyManager.shared.add(dependency: LastPlayedBookProvider() as any LastPlayedBookProviding)
    }

    var body: some Scene {
        WindowGroup {
            // The catalog's `AccentColor` is the adaptive brand coral (NSAccentColorName), which
            // colours prominent buttons, progress and alerts in every window. The `.tint` is still
            // needed: a SwiftUI `Toggle` fills with system green unless tinted, and the accent alone
            // does not reach it (verified in a real window on iOS 26).
            RootView()
                .tint(Color.listenUpOrange)
        }
        // The iPad menu bar: Playback, and the tabs in View. Each command acts on the focused
        // window's shell (see `ListenUpCommands`).
        .commands { ListenUpCommands() }
        // Native background app-refresh. SwiftUI registers the handler for us (the Kotlin
        // BackgroundSyncScheduler is Android-only; iOS wires this natively — see BackgroundSync).
        // The closure runs detached from the view hierarchy, so it resolves Dependencies.shared
        // directly rather than reading @Environment. connectRealtime() is a no-op when signed out
        // (the shared engine auth gate owns that), so no auth check is needed here.
        .backgroundTask(.appRefresh(BackgroundSync.taskIdentifier)) {
            await BackgroundSync.run {
                do {
                    try await Dependencies.shared.syncRepository.connectRealtime()
                } catch is CancellationError {
                } catch {
                    Log.error("Background sync failed", error: error)
                }
            }
        }
    }
}

/// Root view — created after `App.init()` so observers resolve Koin safely.
private struct RootView: View {
    @State private var auth = AuthStateObserver()
    /// Resolved lazily on first authentication (see `.authenticated`) — never at launch, so the
    /// shared ConnectionHealthViewModel graph doesn't touch the keychain before the session exists.
    @State private var connectionHealth: ConnectionHealthObserver?
    /// The app-wide error alert queue. Created eagerly — a plain value holder with no
    /// dependencies; `GlobalErrorObserver` fills it once a session exists.
    @State private var errorAlerts = ErrorAlertCenter()
    /// Resolved lazily on first authentication, mirroring `connectionHealth`: the shared graph must
    /// not be touched before a session exists.
    @State private var globalErrors: GlobalErrorObserver?
    @State private var currentUser = CurrentUserObserver()
    @State private var readiness = LibraryReadinessObserver()
    @State private var hapticsSettings = HapticsSettings()
    @State private var deepLinkRouter = DeepLinkRouter()
    @State private var showReauthSheet = false
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dependencies) private var dependencies

    var body: some View {
        content
            .environment(currentUser)
            .environment(hapticsSettings)
            .environment(deepLinkRouter)
            // One router per process, owned by the one push coordinator: every window's shell
            // observes it and claims taps from it, so a tap lands in exactly one window.
            .environment(PushCoordinator.shared.tapRouter)
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
                // Post-auth is the one moment a notification prompt makes sense (Android parity:
                // AppShell's once-per-session request). No-op on every later transition.
                if newState == .authenticated {
                    PushCoordinator.shared.activate()
                    // The error surface belongs to the authenticated shell, so it is built here
                    // rather than at launch — resolving the bus pre-auth would touch the shared
                    // graph before a session exists.
                    if globalErrors == nil {
                        globalErrors = GlobalErrorObserver(center: errorAlerts)
                    }
                }
                activateSyncIfAuthenticated()
            }
            .onChange(of: scenePhase) { _, newPhase in
                if newPhase == .active {
                    // Reconnect realtime sync on every foreground (single-flight, so safe), and
                    // re-run the library-setup check if we were backgrounded long enough that the
                    // cached readiness could be stale (mirrors Android's MainActivity.onResume).
                    activateSyncIfAuthenticated()
                    readiness.onAppForegrounded()
                    return
                }
                // Leaving foreground: queue the first background refresh. Subsequent ones chain
                // from the .backgroundTask handler (BackgroundSync.run reschedules before working).
                if newPhase == .background {
                    BackgroundSync.schedule()
                    // Record the background timestamp so onAppForegrounded can measure the gap.
                    readiness.onAppBackgrounded()
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

    /// Connect realtime sync + resume downloads when authenticated, through the process's one
    /// sync session — every window asks the same controller, so a second iPad window adds no
    /// second session.
    private func activateSyncIfAuthenticated() {
        guard auth.state == .authenticated else { return }
        SyncSessionController.shared.activate()
    }

    @ViewBuilder
    private var content: some View {
        switch auth.state {
        case .initializing, .checkingServer:
            LaunchScreen()
        case .needsServerUrl:
            ServerFlowCoordinator()
        // Auth screens sit in a navigation stack so their titles are the system's large titles.
        case .needsSetup:
            NavigationStack { SetupView() }
        case .needsLogin:
            AuthFlowCoordinator(openRegistration: auth.openRegistration)
        case .pendingApproval:
            NavigationStack {
                PendingApprovalView(userId: auth.pendingApprovalUserId, email: auth.pendingApprovalEmail)
            }
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
            // Overlay a non-blocking banner when a version skew is detected (Update available).
            // `sessionExpired` never surfaces here — it has the `.sessionLapsed` branch above. The
            // observer is created lazily on first appear (not at RootView root), so the shared
            // ConnectionHealthViewModel graph is only resolved once the user is authenticated.
            authenticatedContent
                .safeAreaInset(edge: .top) {
                    // Only meaningful once the main app is mounted.
                    if isMainAppMounted, let connectionHealth {
                        ConnectionHealthBanner(
                            kind: connectionHealth.kind,
                            onDismiss: { connectionHealth.dismiss() }
                        )
                    }
                }
                .onAppear {
                    if connectionHealth == nil {
                        connectionHealth = ConnectionHealthObserver()
                    }
                }
        }
    }

    /// Gate the authenticated window on library readiness. A first-run admin with no library
    /// (`needsSetup`) is routed into the setup wizard; on completion the readiness latch flips
    /// to `populating` while the initial scan runs, then `ready` and the main app mounts. The
    /// `populating` gate shows the "Building your library" screen instead of an empty shell —
    /// it fires only for the **initial** population (a returning user with books in Room is
    /// `ready` immediately; later background scans never re-arm it). A returning user goes
    /// straight to the app — setup is skipped.
    /// True once the main app (`MainTabView`) is mounted — the only phase where the
    /// connection-health banner is meaningful. The `.checking`/`.needsSetup`/`.populating`
    /// onboarding phases have no running sync session yet.
    private var isMainAppMounted: Bool {
        switch readiness.phase {
        case .ready, .checkFailed: return true
        default: return false
        }
    }

    /// Both the `.authenticated` and `.sessionLapsed` branches render this, so the error alert is
    /// attached here rather than at either call site — neither can be the one that forgets.
    private var authenticatedContent: some View {
        authenticatedPhase
            .errorAlertHost(errorAlerts)
    }

    @ViewBuilder
    private var authenticatedPhase: some View {
        switch readiness.phase {
        case .checking:
            LaunchScreen()
        case .needsSetup:
            LibrarySetupFlowCoordinator(onComplete: { readiness.onLibrarySetupComplete() })
        case .populating:
            LibraryScanView(
                progress: readiness.scanProgress,
                stalled: readiness.isPopulatingStalled,
                onContinue: { readiness.onContinueToPartialLibrary() }
            )
        case .ready, .checkFailed:
            MainTabView()
        }
    }
}

/// Shown while the app initialises: the launch screen's plain system background, continued, with a
/// spinner once the wait is long enough to notice.
///
/// HIG, Launching: "Downplay the launch experience … A launch screen isn't part of an onboarding
/// experience or a splash screen"; "if your app displays a solid color before transitioning to the
/// first screen, create a launch screen that displays only that solid color". The branded gradient
/// and logo it replaces flashed between the system's blank launch screen and the first real screen.
private struct LaunchScreen: View {
    @State private var showsProgress = false

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            if showsProgress {
                ProgressView()
            }
        }
        // A spinner that appears and vanishes within a blink reads as a flicker; show it only once
        // the start-up is slow enough for someone to wonder.
        .task {
            try? await Task.sleep(for: .milliseconds(400))
            showsProgress = true
        }
    }
}
