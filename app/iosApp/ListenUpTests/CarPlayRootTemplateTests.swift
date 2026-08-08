import CarPlay
import Testing
@testable import ListenUp

/// Pins the root-template composition against CarPlay's runtime validation.
///
/// `CPTabBarTemplate.init(templates:)` throws `NSInvalidArgumentException` if any member is a
/// `CPNowPlayingTemplate` — a rule that only fires when a head unit connects, which is exactly
/// the environment CI and a blind dev box never exercise (four crash logs proved it on
/// 2026-08-08). Now-playing is pushed, never embedded; these tests keep that true as the root
/// grows into a tab bar for Library and Search.
@MainActor
@Suite("CarPlay root template")
struct CarPlayRootTemplateTests {
    @Test func theRootIsTheHomeListUntilMoreTabsExist() {
        let home = CPListTemplate(title: "Home", sections: [])

        #expect(CarPlaySceneDelegate.rootTemplate(home: home) === home)
    }

    @Test func aTabBarRootNeverEmbedsTheSystemNowPlayingTemplate() {
        let home = CPListTemplate(title: "Home", sections: [])

        let root = CarPlaySceneDelegate.rootTemplate(home: home)

        if let tabBar = root as? CPTabBarTemplate {
            #expect(!tabBar.templates.contains { $0 is CPNowPlayingTemplate })
        }
    }
}
