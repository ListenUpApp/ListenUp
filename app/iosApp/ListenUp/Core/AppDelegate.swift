import UIKit
@preconcurrency import Shared

/// Routes connecting scenes to their configuration, receives the APNs device-token callbacks, and hands background
/// download sessions their wakes — the delegate duties SwiftUI's `App` lifecycle has no native hook for.
///
/// SwiftUI's `App` lifecycle handles the window scene on its own, but it has no hook for the
/// second scene role CarPlay introduces. `UIApplicationDelegateAdaptor` is the supported way to
/// answer `configurationForConnecting` while leaving SwiftUI in charge of the window scene —
/// more reliable than relying on the scene manifest alone to resolve both roles.
///
/// Keep it minimal otherwise. App startup lives in `ListenUpApp.init()`; push authorization and
/// notification handling live in `PushCoordinator`.
final class AppDelegate: NSObject, UIApplicationDelegate {
    /// Where background-download wakes go; a test substitutes its own.
    var backgroundDownloads: BackgroundDownloadHandoff = .shared

    /// Every launch re-attaches the background download sessions, before the system delivers their events —
    /// including a relaunch in the background to file a finished download, where no scene is ever connected.
    /// `ListenUpApp.init()` has already started Koin.
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        backgroundDownloads.attach()
        #if DEBUG
        DebugExitOnBackground.installIfRequested()
        #endif
        return true
    }

    /// A background download session has events for this app; the system waits for `completionHandler`.
    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        backgroundDownloads.handleEvents(identifier: identifier, completionHandler: completionHandler)
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(
            name: SceneRoleRouting.configurationName(for: connectingSceneSession.role),
            sessionRole: connectingSceneSession.role
        )
        if connectingSceneSession.role == .windowApplication {
            // A cold launch from a home-screen quick action delivers the item here, once; a running
            // app delivers it to the window scene delegate. SwiftUI still owns the window.
            if let shortcutItem = options.shortcutItem {
                QuickActionRouter.shared.receive(shortcutItem)
            }
            configuration.delegateClass = WindowSceneDelegate.self
        }
        return configuration
    }

    /// APNs issued (or rotated) this device's token. Hex-encode and hand it to the shared
    /// layer, which registers it against the user's server when push is enabled there.
    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        let token = hexEncodedAPNsToken(deviceToken)
        Task {
            do {
                try await KoinHelper.shared.onPushTokenReceived(token: token)
            } catch {
                // Best-effort by design: registration re-runs on the next auth/foreground
                // trigger (Never Stranded — SSE carries every event regardless of push).
                Log.error("APNs token hand-off failed", error: error)
            }
        }
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        // No push on this run (simulator, or APNs unreachable). SSE-only behavior — not a bug.
        Log.info("APNs registration unavailable: \(error.localizedDescription)")
    }
}
