import Testing
import SwiftUI
@testable import ListenUp

@Suite("Auth theme tokens")
struct AuthThemeTests {
    /// The global accent is the design coral #F0512F.
    @Test func accentIsDesignCoral() {
        let resolved = Color.listenUpOrange.resolve(in: EnvironmentValues())
        #expect(abs(resolved.red - 0xF0 / 255) < 0.01)
        #expect(abs(resolved.green - 0x51 / 255) < 0.01)
        #expect(abs(resolved.blue - 0x2F / 255) < 0.01)
    }

    /// The pressed tint is the deeper coral #D8431F.
    @Test func pressedTintIsDeeperCoral() {
        let resolved = Color.listenUpTintPressed.resolve(in: EnvironmentValues())
        #expect(abs(resolved.red - 0xD8 / 255) < 0.01)
        #expect(abs(resolved.green - 0x43 / 255) < 0.01)
        #expect(abs(resolved.blue - 0x1F / 255) < 0.01)
    }
}
