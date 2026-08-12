import UIKit
@preconcurrency import Shared

/// Routes connecting scenes to their configuration, and receives the APNs device-token
/// callbacks — the two delegate duties SwiftUI's `App` lifecycle has no native hook for.
///
/// SwiftUI's `App` lifecycle handles the window scene on its own, but it has no hook for the
/// second scene role CarPlay introduces. `UIApplicationDelegateAdaptor` is the supported way to
/// answer `configurationForConnecting` while leaving SwiftUI in charge of the window scene —
/// more reliable than relying on the scene manifest alone to resolve both roles.
///
/// Keep it minimal otherwise. App startup lives in `ListenUpApp.init()`; push authorization and
/// notification handling live in `PushCoordinator`.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        UISceneConfiguration(
            name: SceneRoleRouting.configurationName(for: connectingSceneSession.role),
            sessionRole: connectingSceneSession.role
        )
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
