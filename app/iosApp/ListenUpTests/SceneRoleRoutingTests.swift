import Testing
import UIKit
@testable import ListenUp

/// The app declares two scene roles once CarPlay exists: the SwiftUI window scene the phone
/// shows, and the CarPlay template scene the head unit connects to. `configurationForConnecting`
/// has to hand back the right one for each.
///
/// Worth pinning because the failure is silent. Returning the CarPlay configuration for the
/// window role does not fail to build and does not throw — the phone app simply launches to a
/// black screen, because SwiftUI never gets a window scene to put `RootView` in.
@Suite("SceneRoleRouting")
struct SceneRoleRoutingTests {
    @Test func windowRoleUsesTheDefaultConfiguration() {
        #expect(SceneRoleRouting.configurationName(for: .windowApplication) == SceneRoleRouting.defaultConfigurationName)
    }

    @Test func carPlayRoleUsesTheCarPlayConfiguration() {
        #expect(
            SceneRoleRouting.configurationName(for: .templateApplication) == SceneRoleRouting.carPlayConfigurationName
        )
    }

    /// The two must never collide — a shared name would give one role the other's delegate.
    @Test func theTwoConfigurationsAreDistinct() {
        #expect(SceneRoleRouting.defaultConfigurationName != SceneRoleRouting.carPlayConfigurationName)
    }

    /// An unrecognised role (external display, a future Apple role) falls back to the window
    /// configuration rather than the CarPlay one: a stray CarPlay template scene is a far worse
    /// failure than an unused window scene.
    @Test func anUnknownRoleFallsBackToTheDefaultConfiguration() {
        #expect(
            SceneRoleRouting.configurationName(for: UISceneSession.Role("UIUnknownFutureRole"))
                == SceneRoleRouting.defaultConfigurationName
        )
    }
}
