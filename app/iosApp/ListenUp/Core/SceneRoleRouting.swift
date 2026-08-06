import CarPlay
import UIKit

/// Maps a connecting scene's role to the scene configuration that should serve it.
///
/// The app has two scenes: the SwiftUI window scene the phone shows, and the CarPlay template
/// scene a head unit connects to. Both names below must match the `UISceneConfigurationName`
/// entries in `Info.plist` exactly — a typo is not a build error, it is a scene that never
/// connects.
///
/// Pure and free of any scene object so the mapping is testable on its own; `AppDelegate` does
/// nothing but ask this and hand back a configuration.
enum SceneRoleRouting {
    /// Serves the SwiftUI `WindowGroup`. No delegate class — SwiftUI owns this scene.
    static let defaultConfigurationName = "Default Configuration"

    /// Serves CarPlay, delegated to `CarPlaySceneDelegate`.
    static let carPlayConfigurationName = "CarPlay Configuration"

    /// The configuration name for `role`.
    ///
    /// Anything that is not explicitly the CarPlay role gets the window configuration. That
    /// direction of defaulting is deliberate: an unexpected window scene is inert, whereas
    /// handing a non-CarPlay scene the CarPlay configuration leaves SwiftUI with no window to
    /// place `RootView` in — which presents as the app launching to a black screen, with
    /// nothing in the build or the logs to say why.
    static func configurationName(for role: UISceneSession.Role) -> String {
        role == .carTemplateApplication ? carPlayConfigurationName : defaultConfigurationName
    }
}
