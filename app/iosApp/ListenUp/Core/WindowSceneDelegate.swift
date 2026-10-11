import UIKit

/// Receives the home-screen quick action for a window scene that is already running.
///
/// SwiftUI owns the window scene; this delegate is attached to it only in code (see
/// `AppDelegate.application(_:configurationForConnecting:options:)`), never named in `Info.plist`,
/// so SwiftUI still builds the window. Its one job is the warm-launch quick action: a cold launch
/// delivers the item in the connection options instead, which the app delegate reads.
final class WindowSceneDelegate: NSObject, UIWindowSceneDelegate {
    @MainActor
    func windowScene(
        _ windowScene: UIWindowScene,
        performActionFor shortcutItem: UIApplicationShortcutItem
    ) async -> Bool {
        QuickActionRouter.shared.receive(shortcutItem)
    }
}
