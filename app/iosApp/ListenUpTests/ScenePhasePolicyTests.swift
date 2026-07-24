import Testing
import SwiftUI
@testable import ListenUp

@Suite("Scene phase policy")
struct ScenePhasePolicyTests {
    @Test func backgroundSavesPosition() {
        #expect(ScenePhasePolicy.shouldSavePosition(on: .background))
    }

    @Test func inactiveDoesNotSavePosition() {
        // Regression guard: `.inactive` fires constantly (Control Center, banners, app
        // switcher) and must not arm a redundant save.
        #expect(ScenePhasePolicy.shouldSavePosition(on: .inactive) == false)
    }

    @Test func activeDoesNotSavePosition() {
        #expect(ScenePhasePolicy.shouldSavePosition(on: .active) == false)
    }
}
