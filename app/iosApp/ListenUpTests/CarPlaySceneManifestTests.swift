import CarPlay
import Testing
@testable import ListenUp

/// Pins the *built* scene manifest, not the source `Info.plist`.
///
/// The CarPlay scene is declared in `ListenUp/Info.plist`, but Xcode's
/// `INFOPLIST_KEY_UIApplicationSceneManifest_Generation` build setting can silently replace the
/// hand-written manifest with a generated one whose `UISceneConfigurations` is empty. Nothing
/// fails at build time; the app then throws `NSGenericException` ("Application does not implement
/// CarPlay template application lifecycle methods") the moment a head unit connects. These tests
/// run inside the app host, so `Bundle.main` is the assembled product — the only place the
/// stripping is visible before a car is plugged in.
@Suite("CarPlay scene manifest")
struct CarPlaySceneManifestTests {
    private var carPlayConfiguration: [String: Any]? {
        let manifest = Bundle.main.object(forInfoDictionaryKey: "UIApplicationSceneManifest") as? [String: Any]
        let configurations = manifest?["UISceneConfigurations"] as? [String: Any]
        let carPlayConfigurations =
            configurations?["CPTemplateApplicationSceneSessionRoleApplication"] as? [[String: Any]]
        return carPlayConfigurations?.first
    }

    @Test func theBuiltProductCarriesTheCarPlaySceneConfiguration() {
        #expect(
            carPlayConfiguration?["UISceneConfigurationName"] as? String
                == SceneRoleRouting.carPlayConfigurationName
        )
    }

    /// The delegate class name crosses from the plist to the runtime as a string — a rename or a
    /// module-name change breaks it with no compile error, so resolve it the way UIKit does.
    @Test func theDeclaredDelegateClassResolvesAndSpeaksCarPlay() throws {
        let className = try #require(carPlayConfiguration?["UISceneDelegateClassName"] as? String)
        let resolved = try #require(NSClassFromString(className))
        #expect(resolved is CPTemplateApplicationSceneDelegate.Type)
    }
}
