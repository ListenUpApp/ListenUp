import UIKit

/// Exists for exactly one reason: routing connecting scenes to their configuration.
///
/// SwiftUI's `App` lifecycle handles the window scene on its own, but it has no hook for the
/// second scene role CarPlay introduces. `UIApplicationDelegateAdaptor` is the supported way to
/// answer `configurationForConnecting` while leaving SwiftUI in charge of the window scene —
/// more reliable than relying on the scene manifest alone to resolve both roles.
///
/// Keep it empty otherwise. App startup lives in `ListenUpApp.init()`.
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
}
