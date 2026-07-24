import SwiftUI
import Testing
@testable import ListenUp

@Suite("ValueClassExtensions")
struct ValueClassExtensionsTests {
    @Test func avatarColorIsDeterministicForTheSameId() {
        let a = avatarColorForUserId("user-123")
        let b = avatarColorForUserId("user-123")
        #expect(a == b)
    }

    @Test func avatarColorDiffersAcrossIds() {
        // Not a strict guarantee, but two distinct ids should not collide in practice.
        #expect(avatarColorForUserId("user-123") != avatarColorForUserId("user-999"))
    }
}
