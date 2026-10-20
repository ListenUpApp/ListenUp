import SwiftUI
import Testing
@preconcurrency import Shared
@testable import ListenUp

@Suite("Book Detail cover theme")
struct BookDetailCoverThemeTests {
    @Test func noCoverColoursIsTheNeutralTone() {
        #expect(BookDetailView.heroWash(nil, scheme: .light) == BookDetailHeroTone.color(for: .light))
        #expect(BookDetailView.heroWash(nil, scheme: .dark) == BookDetailHeroTone.color(for: .dark))
    }
}
