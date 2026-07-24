import Testing
import SwiftUI
@testable import ListenUp

@Suite("AuthLayoutMode")
struct AuthLayoutModeTests {
    @Test func regularSizeClassIsRegular() {
        #expect(AuthLayoutMode(horizontalSizeClass: .regular) == .regular)
    }

    @Test func compactSizeClassIsCompact() {
        #expect(AuthLayoutMode(horizontalSizeClass: .compact) == .compact)
    }

    /// Unknown / nil size class defaults to the phone (compact) flow.
    @Test func nilSizeClassDefaultsToCompact() {
        #expect(AuthLayoutMode(horizontalSizeClass: nil) == .compact)
    }
}
